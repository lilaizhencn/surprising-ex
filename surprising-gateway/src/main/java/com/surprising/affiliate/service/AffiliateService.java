package com.surprising.affiliate.service;

import com.surprising.affiliate.config.AffiliateProperties;
import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.model.AffiliateRelationshipException;
import com.surprising.affiliate.repository.AffiliateRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AffiliateService {

  private final AffiliateRepository repository;
  private final AffiliateProperties properties;
  private final Semaphore reads;

  public AffiliateService(
    AffiliateRepository repository,
    AffiliateProperties properties
  ) {
    this.repository = repository;
    this.properties = properties;
    this.reads = new Semaphore(properties.getConcurrentReads());
  }

  @Transactional
  public Profile register(long userId, String code) {
    if (code == null || code.isBlank()) {
      var existing = repository.findProfile(userId);
      if (existing.isPresent()) return existing.get();
    }
    repository.lock();
    repository.ensureProfile(
      userId,
      UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)
    );
    if (code != null && !code.isBlank()) accept(userId, code);
    return repository.profile(userId);
  }

  public Settings settings() {
    return repository.settings(Instant.now());
  }

  public Invitation preview(String raw) {
    return read(() -> previewRead(raw));
  }

  private Invitation previewRead(String raw) {
    String code = code(raw);
    var ordinary = repository.codeOwner(code);
    if (ordinary.isPresent()) return new Invitation(
      code,
      ordinary.get().toString(),
      null,
      false,
      null,
      null,
      null
    );
    var invitation = repository.invitation(code);
    if (
      invitation.acceptedBy() != null ||
      !invitation.expiresAt().isAfter(Instant.now())
    ) throw new IllegalArgumentException("合作邀请已使用或过期");
    requireKol(Long.parseLong(invitation.inviterId()));
    return invitation;
  }

  @Transactional
  public void accept(long user, String raw) {
    repository.lock();
    String code = code(raw);
    var ordinary = repository.codeOwner(code);
    if (ordinary.isPresent()) {
      bind(user, ordinary.get());
      return;
    }
    var invitation = repository.invitation(code);
    if (invitation.acceptedBy() != null) {
      if (invitation.acceptedBy().equals(Long.toString(user))) return;
      throw new IllegalArgumentException("合作邀请已被使用");
    }
    if (
      !invitation.expiresAt().isAfter(Instant.now())
    ) throw new IllegalArgumentException("合作邀请已过期");
    if (
      invitation.targetUserId() != null &&
      !invitation.targetUserId().equals(Long.toString(user))
    ) throw new IllegalArgumentException("此邀请属于其他用户");
    long parent = Long.parseLong(invitation.inviterId());
    requireKol(parent);
    if (!repository.profile(user).kolEnabled()) throw new IllegalArgumentException(
      "请先由后台管理员开通 KOL，再接受团队合作邀请"
    );
    bind(user, parent);
    if (
      repository.parent(user, Instant.now()).isPresent()
    ) throw new IllegalArgumentException("用户已加入团队");
    repository.term(parent, user, invitation.sharePpm(), parent);
    repository.acceptInvitation(code, user);
  }

  private void bind(long user, long inviter) {
    if (user == inviter) throw new IllegalArgumentException("不能邀请自己");
    var existing = repository.inviter(user, Instant.now());
    if (existing.isPresent()) {
      if (existing.get() != inviter) throw new IllegalArgumentException(
        "用户已经绑定其他邀请人，不能改绑"
      );
      return;
    }
    if (
      repository.descendant(user, inviter)
    ) throw new IllegalArgumentException("邀请关系不能形成循环");
    repository.bind(user, inviter);
  }

  @Transactional
  public Invitation invite(long parent, InviteInput input) {
    repository.lock();
    requireKol(parent);
    Long target =
      input.targetUserId() == null || input.targetUserId().isBlank()
        ? null
        : positive(input.targetUserId());
    if (target != null) {
      register(target, null);
      if (target == parent) throw new IllegalArgumentException("不能邀请自己");
      if (
        repository.descendant(target, parent)
      ) throw new IllegalArgumentException("不能邀请上级加入下级团队");
      var existing = repository.inviter(target, Instant.now());
      if (
        existing.isPresent() && existing.get() != parent
      ) throw new IllegalArgumentException("该用户不属于你的直接邀请");
      if (
        repository.parent(target, Instant.now()).isPresent()
      ) throw new IllegalArgumentException(
        "该用户已加入团队，请调整现有直属比例"
      );
    }
    String token = UUID.randomUUID()
      .toString()
      .replace("-", "")
      .toUpperCase(Locale.ROOT);
    repository.createInvitation(token, parent, target, input.sharePpm());
    repository.audit(
      parent,
      "INVITE_PARTNER",
      target,
      "sharePpm=" + input.sharePpm()
    );
    return repository.invitation(token);
  }

  @Transactional
  public void share(long parent, long child, int ppm) {
    repository.lock();
    requireKol(parent);
    var edge = repository
      .parent(child, Instant.now())
      .orElseThrow(() ->
        new IllegalArgumentException("该用户尚未接受合作邀请")
      );
    if (edge.parentId() != parent) throw new IllegalArgumentException(
      "只能设置直属下级比例"
    );
    repository.term(parent, child, ppm, parent);
  }

  @Transactional
  public Profile configure(long user, ProfileInput input, long actor) {
    repository.lock();
    register(user, null);
    repository.configure(user, input, actor);
    return repository.profile(user);
  }

  @Transactional
  public Settings configure(SettingsInput input, long actor) {
    repository.lock();
    repository.settings(input, actor);
    repository.audit(actor, "SETTINGS", null, input.reason());
    return settings();
  }

  @Transactional
  public void budget(String product, long user, long actor, String reason) {
    repository.lock();
    repository.budget(product, user, actor, reason);
  }

  public long requireVisible(long viewer, long subject) {
    if (
      viewer != subject &&
      path(subject, Instant.now())
        .stream()
        .noneMatch(edge -> edge.parentId() == viewer)
    ) throw new IllegalArgumentException("无权查看该团队分支");
    return subject;
  }

  public Map<String, Object> branchOverview(
    long viewer,
    long subject,
    String product
  ) {
    return read(() -> {
      requireVisible(viewer, subject);
      var result = overviewRead(subject, product);
      if (viewer != subject) result.put("invitations", List.of());
      return result;
    });
  }

  public Map<String, Object> overview(long user, String product) {
    return read(() -> overviewRead(user, product));
  }

  private <T> T read(Supplier<T> query) {
    if (!reads.tryAcquire()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "返佣查询繁忙，请稍后刷新"
    );
    try {
      return query.get();
    } finally {
      reads.release();
    }
  }

  private Map<String, Object> overviewRead(long user, String product) {
    var result = new LinkedHashMap<String, Object>();
    result.put("profile", repository.profile(user));
    result.put("settings", settings());
    result.put("counts", repository.counts(user).getFirst());
    result.put("balances", repository.summary(user, product));
    result.put("invitations", repository.invitations(user));
    var currentTeam = team(user, Instant.now());
    var currentPath = currentTeam.terms();
    var rootNode = currentTeam.root();
    var currentSettings = settings();
    int rootRate =
      rootNode.ratePpm() != null
        ? rootNode.ratePpm()
        : rootNode.kolEnabled()
          ? currentSettings.kolRatePpm()
          : currentSettings.referralRatePpm();
    result.put("rootRatePpm", rootRate);
    result.put("teamActive", currentTeam.active());
    result.put(
      "teamPath",
      currentPath
        .stream()
        .map(edge ->
          Map.of(
            "parentId",
            Long.toString(edge.parentId()),
            "childId",
            Long.toString(edge.childId()),
            "sharePpm",
            edge.sharePpm()
          )
        )
        .toList()
    );
    return result;
  }

  public Page<Map<String, Object>> members(
    long viewer,
    long subject,
    long after
  ) {
    return read(() -> membersRead(viewer, subject, after));
  }

  private Page<Map<String, Object>> membersRead(
    long viewer,
    long subject,
    long after
  ) {
    requireVisible(viewer, subject);
    var rows = repository.members(subject, after, 51);
    var visible = rows.subList(0, Math.min(50, rows.size()));
    var contributions = new HashMap<String, List<Map<String, Object>>>();
    var ids = visible
      .stream()
      .map(row -> Long.parseLong(row.get("userId").toString()))
      .toList();
    for (var row : repository.memberContributions(subject, ids)) {
      String user = row.get("userId").toString();
      contributions.computeIfAbsent(user, key -> new ArrayList<>()).add(row);
    }
    for (var row : visible)
      row.put(
        "contributions",
        contributions.getOrDefault(row.get("userId").toString(), List.of())
      );
    boolean more = rows.size() > 50;
    return new Page<>(
      List.copyOf(rows.subList(0, Math.min(50, rows.size()))),
      more ? rows.get(49).get("userId").toString() : null
    );
  }

  public List<TeamTerm> path(long user, Instant at) {
    return team(user, at).terms();
  }

  public TeamPath team(long user, Instant at) {
    var rows = repository.lineage(user, at, properties.getMaxPathNodes() + 1);
    if (
      rows.size() > properties.getMaxPathNodes()
    ) throw new AffiliateRelationshipException(
      "团队路径超过本机安全处理上限，整笔核算已暂停，未截断分账"
    );
    var nodes = new HashMap<Long, PathNode>();
    for (var row : rows)
      if (
        nodes.putIfAbsent(row.node().userId(), row) != null
      ) throw new AffiliateRelationshipException(
        "团队历史合作区间重叠，核算已暂停"
      );
    var reverse = new ArrayList<TeamTerm>();
    var seen = new HashSet<Long>();
    boolean active = true;
    long current = user;
    while (true) {
      if (!seen.add(current)) throw new AffiliateRelationshipException(
        "团队关系存在循环，核算已暂停"
      );
      var row = nodes.get(current);
      if (row == null) throw new AffiliateRelationshipException(
        "团队路径不完整，核算已暂停"
      );
      active &= row.node().kolEnabled();
      if (row.parent() == null) {
        Collections.reverse(reverse);
        return new TeamPath(
          row.node(),
          List.copyOf(reverse),
          reverse.isEmpty() || active
        );
      }
      reverse.add(row.parent());
      current = row.parent().parentId();
    }
  }

  private void requireKol(long user) {
    if (
      !repository.profile(user).kolEnabled() ||
      !team(user, Instant.now()).active()
    ) throw new IllegalArgumentException(
      "当前团队合作功能已停用，请联系平台或上级处理"
    );
  }

  public static long positive(String value) {
    try {
      long id = Long.parseLong(value);
      if (id > 0) return id;
    } catch (NumberFormatException ignored) {}
    throw new IllegalArgumentException("用户 ID 必须为正整数");
  }

  private static String code(String value) {
    if (
      value == null || !value.trim().matches("[A-Za-z0-9]{32}")
    ) throw new IllegalArgumentException("邀请码格式不正确");
    return value.trim().toUpperCase(Locale.ROOT);
  }
}

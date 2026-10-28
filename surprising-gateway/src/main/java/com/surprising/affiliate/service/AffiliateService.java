package com.surprising.affiliate.service;

import com.surprising.affiliate.model.AffiliateModels.*;
import com.surprising.affiliate.repository.AffiliateRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AffiliateService {

  private final AffiliateRepository repository;

  public AffiliateService(AffiliateRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public Profile register(long userId, String code) {
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
    bind(user, parent);
    if (
      repository.parent(user, Instant.now()).isPresent()
    ) throw new IllegalArgumentException("用户已加入团队");
    if (repository.suspended(user)) throw new IllegalArgumentException(
      "平台已停用该用户的 KOL 功能，请联系平台处理"
    );
    var current = repository.profile(user);
    repository.configure(
      user,
      new ProfileInput(
        true,
        current.autoSettlement(),
        current.ratePpm(),
        "接受团队合作并继承分支管理权限"
      ),
      user
    );
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

  public boolean activeTeam(List<TeamTerm> path, Instant at) {
    if (path.isEmpty()) return true;
    if (
      !repository.node(path.getFirst().parentId(), at).kolEnabled()
    ) return false;
    for (var edge : path)
      if (!repository.node(edge.childId(), at).kolEnabled()) return false;
    return true;
  }

  public Map<String, Object> branchOverview(
    long viewer,
    long subject,
    String product
  ) {
    requireVisible(viewer, subject);
    var result = overview(subject, product);
    if (viewer != subject) result.put("invitations", List.of());
    return result;
  }

  public Map<String, Object> overview(long user, String product) {
    var result = new LinkedHashMap<String, Object>();
    result.put("profile", repository.profile(user));
    result.put("settings", settings());
    result.put("counts", repository.counts(user).getFirst());
    result.put("balances", repository.summary(user, product));
    result.put("invitations", repository.invitations(user));
    var currentPath = path(user, Instant.now());
    long root = currentPath.isEmpty()
      ? user
      : currentPath.getFirst().parentId();
    var rootNode = repository.node(root, Instant.now());
    var currentSettings = settings();
    int rootRate =
      rootNode.ratePpm() != null
        ? rootNode.ratePpm()
        : rootNode.kolEnabled()
          ? currentSettings.kolRatePpm()
          : currentSettings.referralRatePpm();
    result.put("rootRatePpm", rootRate);
    result.put("teamActive", activeTeam(currentPath, Instant.now()));
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
    var reverse = new ArrayList<TeamTerm>();
    var seen = new HashSet<Long>();
    var terms = new HashMap<Long, TeamTerm>();
    for (var edge : repository.teamPath(user, at))
      terms.put(edge.childId(), edge);
    long current = user;
    while (true) {
      if (!seen.add(current)) throw new IllegalStateException(
        "团队关系存在循环"
      );
      var parent = terms.get(current);
      if (parent == null) break;
      reverse.add(parent);
      current = parent.parentId();
    }
    Collections.reverse(reverse);
    return List.copyOf(reverse);
  }

  private void requireKol(long user) {
    if (
      !repository.profile(user).kolEnabled() ||
      !activeTeam(path(user, Instant.now()), Instant.now())
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

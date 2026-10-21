package com.surprising.gateway.provider.announcement.service;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import com.surprising.gateway.provider.announcement.repository.*;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem;
import com.surprising.gateway.provider.announcement.exception.AnnouncementProblem.Kind;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 聚合公告状态、投放/翻译、必需审计与本人已读；单表访问均由各自Repository承担。 */
@Service
public class AnnouncementService {
    private static final List<String> REQUIRED_LOCALES=List.of("en-US","zh-CN");
    private final AnnouncementRepository repository;
    private final AnnouncementTranslationRepository translations;
    private final AnnouncementTargetRepository targets;
    private final AnnouncementReadRepository reads;
    private final AnnouncementAuditRepository audit;
    private final AuthService auth;
    private final AdminApprovalService approvals;

    public AnnouncementService(AnnouncementRepository repository,AnnouncementTranslationRepository translations,
            AnnouncementTargetRepository targets,AnnouncementReadRepository reads,AnnouncementAuditRepository audit,
            AuthService auth,AdminApprovalService approvals) {
        this.repository=repository;this.translations=translations;this.targets=targets;this.reads=reads;
        this.audit=audit;this.auth=auth;this.approvals=approvals;
    }

    public AdminPage adminList(String authorization,String status,String query,int offset,int limit) {
        admin(authorization,"admin.announcements.read");
        var page=repository.listAdmin(status,query,offset,limit);
        var items=adminViews(page.rows());
        return new AdminPage(page.count(),items,offset,limit,offset+items.size()<page.count());
    }
    public Announcement adminGet(String authorization,long id) {
        admin(authorization,"admin.announcements.read");return findAdmin(id);
    }
    public List<AuditEntry> audit(String authorization,long id) {
        admin(authorization,"admin.announcements.read");requireRow(id);return audit.list(id);
    }

    @Transactional
    public Announcement create(String authorization,Content content) {
        var actor=admin(authorization,"admin.announcements.write");var now=Instant.now();
        long id=repository.create(content,actor.userId(),now);
        replaceContent(id,content);
        return auditResult(id,"CREATE",actor,null,now);
    }
    @Transactional
    public Announcement update(String authorization,long id,Content content,long version) {
        var actor=admin(authorization,"admin.announcements.write");var current=requireRow(id);
        if(current.version()!=version)throw conflict("announcement was changed by another operator");
        if(current.status().equals("PUBLISHED") && !content.translations().stream().map(Translation::locale).toList().containsAll(REQUIRED_LOCALES))
            throw invalid("published announcements must retain complete zh-CN and en-US translations");
        var now=Instant.now();
        if(!repository.update(id,content,version,actor.userId(),now))throw conflict("announcement was changed by another operator");
        replaceContent(id,content);
        reads.clear(id);
        return auditResult(id,"UPDATE",actor,null,now);
    }
    @Transactional
    public Announcement publish(String authorization,long id,long version,String reason,
                               AdminApprovalService.AdminRequestMetadata metadata,byte[] rawBody) {
        var actor=approvedActor(authorization,metadata,rawBody);var current=findAdmin(id);
        if(current.version()!=version)throw conflict("announcement was changed by another operator");
        if(current.status().equals("WITHDRAWN"))throw invalid("withdrawn announcements must be saved as a draft before publishing");
        for(String locale:REQUIRED_LOCALES)
            if(current.translations().stream().noneMatch(t->t.locale().equals(locale) && !t.title().isBlank() && !t.body().isBlank()))
                throw invalid("publish requires complete zh-CN and en-US translations");
        if(current.placements().isEmpty() || current.productLines().isEmpty() || current.platforms().isEmpty())
            throw invalid("announcement targeting is incomplete");
        if(!current.placements().contains("CENTER"))throw invalid("published announcements must be available in the announcement center");
        var now=Instant.now();
        if(!repository.publish(id,version,actor.userId(),now))throw conflict("announcement cannot be published in its current state");
        return auditResult(id,"PUBLISH",actor,reason,now);
    }
    @Transactional
    public Announcement withdraw(String authorization,long id,long version,String reason,
                                AdminApprovalService.AdminRequestMetadata metadata,byte[] rawBody) {
        var actor=approvedActor(authorization,metadata,rawBody);requireRow(id);var now=Instant.now();
        if(!repository.withdraw(id,version,actor.userId(),now))throw conflict("announcement was changed or already withdrawn");
        return auditResult(id,"WITHDRAW",actor,reason,now);
    }
    private void replaceContent(long id,Content content) {
        translations.replace(id,content.translations());targets.replace(id,content);
    }
    private Announcement auditResult(long id,String action,JwtPrincipal actor,String reason,Instant now) {
        var snapshot=findAdmin(id);
        audit.append(snapshot,action,actor.userId(),actor.username(),reason,now);
        return snapshot;
    }

    public ClientPage clientList(String authorization,ClientFilter filter,int offset,int limit) {
        var userId=optionalUserId(authorization);var now=Instant.now();
        var items=repository.listClient(filter,userId,offset,limit,now).stream().map(row->clientView(row,filter)).toList();
        int count=repository.countClient(filter,userId,false,now);
        int unread=userId==null?0:repository.countClient(filter,userId,true,now);
        return new ClientPage(items,unread,offset,limit,offset+items.size()<count);
    }
    public Announcement clientGet(String authorization,long id,ClientFilter filter) {
        return findClient(id,filter,optionalUserId(authorization));
    }
    public int unreadCount(String authorization,ClientFilter filter) {
        return repository.countClient(filter,user(authorization).userId(),true,Instant.now());
    }
    @Transactional
    public Announcement markRead(String authorization,long id,ClientFilter filter) {
        long userId=user(authorization).userId();
        if(!repository.lockForReading(id))throw missing();
        findClient(id,filter,userId); // 仅当前可见公告可写本人已读；主表共享锁覆盖资格检查、写入和结果读取。
        reads.insert(id,userId,Instant.now());
        return findClient(id,filter,userId);
    }
    @Transactional
    public int markAllRead(String authorization,ClientFilter filter) {
        return reads.markAllRead(user(authorization).userId(),filter,Instant.now());
    }
    private Announcement findClient(long id,ClientFilter filter,Long userId) {
        return repository.findClient(id,filter,userId,Instant.now()).map(row->clientView(row,filter)).orElseThrow(this::missing);
    }
    private BaseRow requireRow(long id) { return repository.find(id).orElseThrow(this::missing); }
    private Announcement findAdmin(long id) { return adminViews(List.of(requireRow(id))).getFirst(); }
    private List<Announcement> adminViews(List<BaseRow> rows) {
        if(rows.isEmpty())return List.of();
        var ids=rows.stream().map(BaseRow::id).toList();
        var translationsById=translations.findFor(ids).stream().collect(Collectors.groupingBy(TranslationRow::announcementId,
                Collectors.mapping(TranslationRow::translation,Collectors.toList())));
        var targetsById=targets.findFor(ids).stream().collect(Collectors.groupingBy(TargetRow::announcementId));
        return rows.stream().map(row->new Announcement(row.id(),row.category(),row.status(),row.priority(),
                targetValues(targetsById,row.id(),"PLACEMENT"),targetValues(targetsById,row.id(),"PRODUCT_LINE"),
                targetValues(targetsById,row.id(),"PLATFORM"),row.startsAt(),row.expiresAt(),row.version(),
                translationsById.getOrDefault(row.id(),List.of()),null,null,null,null,null,row.createdAt(),row.updatedAt(),
                effectiveStatus(row))).toList();
    }
    private List<String> targetValues(Map<Long,List<TargetRow>> rows,long id,String type) {
        return rows.getOrDefault(id,List.of()).stream().filter(row->row.type().equals(type)).map(TargetRow::value).toList();
    }
    private Announcement clientView(ClientRow row,ClientFilter filter) {
        var base=row.base();
        return new Announcement(base.id(),base.category(),base.status(),base.priority(),List.of(filter.placement()),
                List.of(filter.productLine()),List.of(),base.startsAt(),base.expiresAt(),base.version(),List.of(),
                row.locale(),row.title(),row.summary(),row.body(),row.readAt(),base.createdAt(),base.updatedAt(),effectiveStatus(base));
    }
    private String effectiveStatus(BaseRow row) {
        if(row.status().equals("WITHDRAWN") || row.status().equals("DRAFT"))return row.status();
        var now=Instant.now();
        if(row.startsAt().isAfter(now))return "SCHEDULED";
        if(row.expiresAt()!=null && !row.expiresAt().isAfter(now))return "EXPIRED";
        return "PUBLISHED";
    }
    private JwtPrincipal admin(String authorization,String permission) {
        try { return auth.requireAdminPermission(authorization,permission); }
        catch(IllegalArgumentException ex) { throw new AnnouncementProblem(Kind.AUTH_REQUIRED,ex.getMessage()); }
        catch(IllegalStateException ex) { throw new AnnouncementProblem(Kind.ACCESS_DENIED,ex.getMessage()); }
    }
    private JwtPrincipal approvedActor(String authorization,AdminApprovalService.AdminRequestMetadata metadata,byte[] body) {
        return approvals.requireWrite(authorization,"admin.announcements.publish","gateway-admin",metadata,body);
    }
    private JwtPrincipal user(String authorization) {
        try { return auth.authenticateBearer(authorization); }
        catch(IllegalArgumentException ex) { throw new AnnouncementProblem(Kind.AUTH_REQUIRED,ex.getMessage()); }
        catch(IllegalStateException ex) { throw new AnnouncementProblem(Kind.ACCESS_DENIED,ex.getMessage()); }
    }
    private Long optionalUserId(String authorization) { return authorization==null||authorization.isBlank()?null:user(authorization).userId(); }
    private AnnouncementProblem missing() { return new AnnouncementProblem(Kind.MISSING,"announcement not found"); }
    private AnnouncementProblem invalid(String message) { return new AnnouncementProblem(Kind.INVALID_STATE,message); }
    private AnnouncementProblem conflict(String message) { return new AnnouncementProblem(Kind.CONFLICT,message); }
}

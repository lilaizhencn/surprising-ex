package com.surprising.trading.maintenance;

import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MaintenanceRepository {
    private final JdbcTemplate jdbc;
    public MaintenanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MaintenanceTask create(ProductLine line, String admin, MaintenanceRequest request) {
        jdbc.update("""
                INSERT INTO trading_maintenance_task(product_line,request_id,symbol,user_id,mode,price_ticks,reason,admin_user_id)
                VALUES(?,?::uuid,?,?,?,?,?,?) ON CONFLICT(product_line,request_id) DO NOTHING
                """, line.name(), request.requestId().toString(), request.symbol(), Long.parseLong(request.userId()),
                request.mode().name(), Long.parseLong(request.priceTicks()), request.reason(), admin);
        var task = jdbc.queryForObject("SELECT * FROM trading_maintenance_task WHERE product_line=? AND request_id=?::uuid",
                this::map, line.name(), request.requestId().toString());
        if (!task.request().equals(request) || !task.adminUserId().equals(admin)) throw new IllegalArgumentException("requestId was already used for a different operation");
        return task;
    }
    public MaintenanceTask get(ProductLine line, long id, boolean lock) {
        return jdbc.query("SELECT * FROM trading_maintenance_task WHERE product_line=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                this::map, line.name(), id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("maintenance task not found"));
    }
    public MaintenanceTask next(ProductLine line) {
        return jdbc.query("SELECT * FROM trading_maintenance_task WHERE product_line=? AND status='RUNNING' ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                this::map, line.name()).stream().findFirst().orElse(null);
    }
    public List<MaintenanceTask> list(ProductLine line, long beforeId) {
        return jdbc.query("SELECT * FROM trading_maintenance_task WHERE product_line=? AND (?=0 OR id<?) ORDER BY id DESC LIMIT 50",
                this::map, line.name(), beforeId, beforeId);
    }
    public void update(MaintenanceTask t, String status, String phase, long cursor, int round, String error) {
        jdbc.update("""
                UPDATE trading_maintenance_task SET status=?,phase=?,cursor_user_id=?,round_no=?,step=step+1,
                error=?,updated_at=now() WHERE id=? AND product_line=?
                """, status, phase, cursor, round, error, t.taskId(), t.productLine().name());
    }
    public void phase(MaintenanceTask t, String phase) { update(t,"RUNNING",phase,t.cursorUserId(),t.roundNo(),null); }
    public void block(MaintenanceTask t, String error) {
        // Keep the command identity and the immutable payload on unknown outcomes.
        jdbc.update("UPDATE trading_maintenance_task SET status='BLOCKED',error=?,updated_at=now() WHERE id=?", error, t.taskId());
    }
    public record Action(String key, String requestJson, String resultJson, boolean completed) { }
    public void addAction(MaintenanceTask t, String key, String request) {
        jdbc.update("INSERT INTO trading_maintenance_action(task_id,action_key,request_json) VALUES(?,?,?) ON CONFLICT DO NOTHING",t.taskId(),key,request);
    }
    public Action pendingAction(MaintenanceTask t) {
        return jdbc.query("SELECT * FROM trading_maintenance_action WHERE task_id=? AND NOT completed ORDER BY action_key LIMIT 1",
                (r,n) -> new Action(r.getString("action_key"),r.getString("request_json"),r.getString("result_json"),r.getBoolean("completed")),t.taskId()).stream().findFirst().orElse(null);
    }
    public void completeAction(MaintenanceTask t, Action a, String result) {
        jdbc.update("UPDATE trading_maintenance_action SET completed=true,result_json=?,updated_at=now() WHERE task_id=? AND action_key=?",result,t.taskId(),a.key());
        jdbc.update("UPDATE trading_maintenance_task SET updated_at=now() WHERE id=?",t.taskId());
    }
    public List<Action> actions(MaintenanceTask t, String afterKey) {
        return jdbc.query("SELECT * FROM trading_maintenance_action WHERE task_id=? AND action_key>? ORDER BY action_key LIMIT 50",
                (r,n) -> new Action(r.getString("action_key"),r.getString("request_json"),r.getString("result_json"),r.getBoolean("completed")),t.taskId(),afterKey);
    }
    private MaintenanceTask map(ResultSet r, int row) throws SQLException {
        return new MaintenanceTask(r.getString("id"),ProductLine.valueOf(r.getString("product_line")),
                new MaintenanceRequest(UUID.fromString(r.getString("request_id")),r.getString("symbol"),r.getString("user_id"),
                        MaintenanceRequest.Mode.valueOf(r.getString("mode")),r.getString("price_ticks"),r.getString("reason")),
                r.getString("admin_user_id"),r.getString("status"),r.getString("phase"),r.getLong("cursor_user_id"),
                r.getInt("round_no"),r.getLong("step"),r.getString("error"),
                r.getString("created_at"),r.getString("updated_at"));
    }
}

package com.surprising.trading.maintenance.repository;

import com.surprising.trading.maintenance.model.MaintenanceAction;
import com.surprising.trading.maintenance.model.MaintenanceTask;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问trading_maintenance_action；不改变父任务的状态或调度顺序。 */
@Repository
public class MaintenanceActionRepository {
    private final JdbcTemplate jdbc;

    public MaintenanceActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addAction(MaintenanceTask t, String key, String request) {
        jdbc.update("INSERT INTO trading_maintenance_action(task_id,action_key,request_json) VALUES(?,?,?) ON CONFLICT DO NOTHING",t.taskId(),key,request);
    }
    public MaintenanceAction pendingAction(MaintenanceTask t) {
        return jdbc.query("SELECT * FROM trading_maintenance_action WHERE task_id=? AND NOT completed ORDER BY action_key LIMIT 1",
                (r,n) -> new MaintenanceAction(r.getString("action_key"),r.getString("request_json"),r.getString("result_json"),r.getBoolean("completed")),t.taskId()).stream().findFirst().orElse(null);
    }
    public void completeAction(MaintenanceTask t, MaintenanceAction a, String result) {
        jdbc.update("UPDATE trading_maintenance_action SET completed=true,result_json=?,updated_at=now() WHERE task_id=? AND action_key=?",result,t.taskId(),a.key());
    }
    public List<MaintenanceAction> actions(MaintenanceTask t, String afterKey) {
        return jdbc.query("SELECT * FROM trading_maintenance_action WHERE task_id=? AND action_key>? ORDER BY action_key LIMIT 50",
                (r,n) -> new MaintenanceAction(r.getString("action_key"),r.getString("request_json"),r.getString("result_json"),r.getBoolean("completed")),t.taskId(),afterKey);
    }
}

import io.aeron.cluster.RecordingLog;
public class SnapshotEvidence {
 public static void main(String[] args) {
  try (var log = new RecordingLog(new java.io.File(args[0]), false)) {
   var service = log.getLatestSnapshot(0); var consensus = log.getLatestSnapshot(-1);
   if (service == null || consensus == null || !service.isValid || !consensus.isValid
    || service.logPosition != consensus.logPosition || service.leadershipTermId != consensus.leadershipTermId
    || service.timestamp != consensus.timestamp) throw new IllegalStateException("unpaired snapshots");
   System.out.printf("{\"serviceRecordingId\":%d,\"consensusRecordingId\":%d,\"logPosition\":%d,\"leadershipTermId\":%d,\"timestamp\":%d,\"paired\":true}%%n".replace("%%n", "%n"), service.recordingId, consensus.recordingId, service.logPosition, service.leadershipTermId, service.timestamp);
  }
 }
}
package com.surprising.aeron.protocol;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RealtimeFrameCodecTest {
 @Test void roundTripsEveryProductAndKindWithoutPrecisionLoss() {
  for(var p:ProductLine.values()) for(var k:RealtimeFrame.Kind.values()) {
   byte[] payload={1,2,3}; var f=new RealtimeFrame(p,k,42,Long.MAX_VALUE,31,123,4,"BTC-USDT","订单",payload);
   payload[0]=9; var decoded=RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(f));
   assertThat(decoded).usingRecursiveComparison().isEqualTo(f);assertThat(decoded.payload()).containsExactly((byte)1,(byte)2,(byte)3);
  }
 }
 @Test void rejectsMalformedAndOversizedEnvelopes() {
  var f=new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.ORDER,1,2,3,4,0,"BTC-USDT","1",new byte[2]);
  byte[] valid=RealtimeFrameCodec.encode(f);
  for(int n=0;n<valid.length;n++) {byte[] truncated=java.util.Arrays.copyOf(valid,n);
   assertThatThrownBy(()->RealtimeFrameCodec.decode(truncated)).isInstanceOf(IllegalArgumentException.class);}
  var extra=java.util.Arrays.copyOf(valid,valid.length+1); assertThatThrownBy(()->RealtimeFrameCodec.decode(extra)).isInstanceOf(IllegalArgumentException.class);
  valid[8]=(byte)127; assertThatThrownBy(()->RealtimeFrameCodec.decode(valid)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->RealtimeFrameCodec.encode(new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.USER,1,1,0,1,0,"","",new byte[RealtimeFrameCodec.MAX_FRAME_BYTES]))).isInstanceOf(IllegalArgumentException.class);
 }
}

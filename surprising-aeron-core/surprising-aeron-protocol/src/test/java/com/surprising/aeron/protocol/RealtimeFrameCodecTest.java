package com.surprising.aeron.protocol;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RealtimeFrameCodecTest {
 @Test void orderEnvelopeMatchesExistingWireFormatForAllProductsAndUtf8() {
  for(var product:ProductLine.values()) for(String text:new String[]{"1","币对😀","bad\uD800", "x".repeat(64)}) {
   var order=new CoreOrderStateView(Long.MAX_VALUE,product,42,text,CoreOrderSide.SELL,
     100,2,1,1,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,
     CoreTimeInForce.GTC,false,"客户😀",new java.util.UUID(1,2),0,0,1,2,3,"OPEN",9);
   byte[] expected=RealtimeFrameCodec.encode(new RealtimeFrame(product,RealtimeFrame.Kind.ORDER,42,
     Long.MAX_VALUE,31,123,4,text,Long.toString(order.orderId()),CoreStateQueryCodec.encodeOrderState(order)));
   byte[] actual=RealtimeFrameCodec.encodeOrder(order,Long.MAX_VALUE,31,123,4);
   assertThat(actual).containsExactly(expected);
   assertThat(RealtimeFrameCodec.decode(actual).payload()).containsExactly(CoreStateQueryCodec.encodeOrderState(order));
   actual[actual.length-1]^=1;
   assertThat(RealtimeFrameCodec.encodeOrder(order,Long.MAX_VALUE,31,123,4)).containsExactly(expected);
   assertThatThrownBy(()->RealtimeFrameCodec.encodeOrder(order,-1,0,0,0)).isInstanceOf(IllegalArgumentException.class);
  }
 }
 @Test void directEncodingPreservesWireBytesAndPayloadIsolation() {
  for(var p:ProductLine.values()) for(var k:RealtimeFrame.Kind.values()) {
   byte[] payload={1,2,3};
   var frame=new RealtimeFrame(p,k,42,Long.MAX_VALUE,31,123,4,"1","订单😀",payload);
   byte[] encoded=RealtimeFrameCodec.encode(p,k,42,Long.MAX_VALUE,31,123,4,"1","订单😀",payload);
   assertThat(encoded).containsExactly(RealtimeFrameCodec.encode(frame));
   payload[0]=9;
   assertThat(RealtimeFrameCodec.decode(encoded).payload()).containsExactly((byte)1,(byte)2,(byte)3);
  }
  assertThatThrownBy(()->RealtimeFrameCodec.encode(ProductLine.SPOT,RealtimeFrame.Kind.ORDER,
    -1,0,0,0,0,"","",new byte[0])).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->RealtimeFrameCodec.encode(ProductLine.SPOT,RealtimeFrame.Kind.ORDER,
    1,0,0,0,0,"","",new byte[RealtimeFrameCodec.MAX_FRAME_BYTES])).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void roundTripsEveryProductAndKindWithoutPrecisionLoss() {
  for(var p:ProductLine.values()) for(var k:RealtimeFrame.Kind.values()) {
   byte[] payload={1,2,3}; var f=new RealtimeFrame(p,k,42,Long.MAX_VALUE,31,123,4,"1","订单",payload);
   payload[0]=9; var decoded=RealtimeFrameCodec.decode(RealtimeFrameCodec.encode(f));
   assertThat(decoded).usingRecursiveComparison().isEqualTo(f);assertThat(decoded.payload()).containsExactly((byte)1,(byte)2,(byte)3);
  }
 }
 @Test void decodesBoundedHeapDirectAndReadOnlyBuffersWithoutBorrowingTheirStorage() {
  for(var product:ProductLine.values()) for(var kind:RealtimeFrame.Kind.values()) {
   var expected=new RealtimeFrame(product,kind,42,123,3,4,0,"币对😀","订单",new byte[]{1,2,3});
   byte[] encoded=RealtimeFrameCodec.encode(expected);
   for(boolean direct:new boolean[]{false,true}) {
    var buffer=direct?java.nio.ByteBuffer.allocateDirect(encoded.length+24):java.nio.ByteBuffer.allocate(encoded.length+24);
    buffer.position(11);buffer.put(encoded);buffer.limit(buffer.position());buffer.position(11);
    var borrowed=buffer.asReadOnlyBuffer();
    var decoded=RealtimeFrameCodec.decode(borrowed);
    assertThat(borrowed.position()).isEqualTo(11);
    assertThat(decoded).usingRecursiveComparison().isEqualTo(expected);
    buffer.put(11+encoded.length-1,(byte)99);
    byte[] exposed=decoded.payload();exposed[0]=99;
    assertThat(decoded.payload()).containsExactly((byte)1,(byte)2,(byte)3);
    buffer.limit(buffer.limit()-1);
    assertThatThrownBy(()->RealtimeFrameCodec.decode(buffer)).isInstanceOf(IllegalArgumentException.class);
   }
  }
 }
 @Test void rejectsMalformedAndOversizedEnvelopes() {
  var f=new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.ORDER,1,2,3,4,0,"1","1",new byte[2]);
  byte[] valid=RealtimeFrameCodec.encode(f);
  for(int n=0;n<valid.length;n++) {byte[] truncated=java.util.Arrays.copyOf(valid,n);
   assertThatThrownBy(()->RealtimeFrameCodec.decode(truncated)).isInstanceOf(IllegalArgumentException.class);}
  var extra=java.util.Arrays.copyOf(valid,valid.length+1); assertThatThrownBy(()->RealtimeFrameCodec.decode(extra)).isInstanceOf(IllegalArgumentException.class);
  valid[8]=(byte)127; assertThatThrownBy(()->RealtimeFrameCodec.decode(valid)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->RealtimeFrameCodec.encode(new RealtimeFrame(ProductLine.SPOT,RealtimeFrame.Kind.USER,1,1,0,1,0,"","",new byte[RealtimeFrameCodec.MAX_FRAME_BYTES]))).isInstanceOf(IllegalArgumentException.class);
 }
}

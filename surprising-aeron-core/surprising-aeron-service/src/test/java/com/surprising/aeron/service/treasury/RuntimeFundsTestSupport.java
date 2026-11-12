package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.TradeSettlement;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** 测试从资金缓冲读取独立分录对照值；生产路径不为断言物化第二份资金模型。 */
public final class RuntimeFundsTestSupport {
    private RuntimeFundsTestSupport() { }

    public static List<Posting> postings(FundsTotal funds) {
        try {
            int size = (int) value(funds, "size");
            int[] assets = (int[]) value(funds, "assetIds");
            byte[] owners = (byte[]) value(funds, "ownerKinds");
            long[] ids = (long[]) value(funds, "ownerIds");
            byte[] ledgers = (byte[]) value(funds, "subledgers");
            long[] amounts = (long[]) value(funds, "units");
            var ownerKinds = TradeSettlement.Posting.OwnerKind.values();
            var subledgers = TradeSettlement.Posting.Subledger.values();
            var result = new ArrayList<Posting>(size);
            for (int index = 0; index < size; index++) {
                result.add(new Posting(assets[index], ownerKinds[owners[index]], ids[index],
                        subledgers[ledgers[index]], amounts[index]));
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot inspect funds accumulator", failure);
        }
    }

    private static Object value(FundsTotal funds, String name)
            throws ReflectiveOperationException {
        Field field = FundsTotal.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(funds);
    }

    public record Posting(int assetId, TradeSettlement.Posting.OwnerKind ownerKind, long ownerId,
                          TradeSettlement.Posting.Subledger subledger, long units) { }
}

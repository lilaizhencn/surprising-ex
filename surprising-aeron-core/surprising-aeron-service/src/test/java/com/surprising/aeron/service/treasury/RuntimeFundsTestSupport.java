package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.settlement.FundsPosting;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** 测试从资金缓冲读取独立分录对照值；生产路径不为断言物化第二份资金模型。 */
public final class RuntimeFundsTestSupport {
    private RuntimeFundsTestSupport() { }

    public static List<Posting> postings(RuntimeFundsAccumulator funds) {
        try {
            int size = (int) value(funds, "size");
            int[] assets = (int[]) value(funds, "assetIds");
            byte[] owners = (byte[]) value(funds, "ownerKinds");
            long[] ids = (long[]) value(funds, "ownerIds");
            byte[] ledgers = (byte[]) value(funds, "subledgers");
            long[] amounts = (long[]) value(funds, "units");
            var ownerKinds = FundsPosting.OwnerKind.values();
            var subledgers = FundsPosting.Subledger.values();
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

    private static Object value(RuntimeFundsAccumulator funds, String name)
            throws ReflectiveOperationException {
        Field field = RuntimeFundsAccumulator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(funds);
    }

    public record Posting(int assetId, FundsPosting.OwnerKind ownerKind, long ownerId,
                          FundsPosting.Subledger subledger, long units) { }
}

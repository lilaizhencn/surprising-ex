package com.surprising.aeron.service.command.support;

/**
 * 内部集合边界的只读 long 访问契约，用长度和下标读取避免装箱。它本身不承诺底层内容不可变、线程安全或已经复制，持有期限由实现和调用方约定。
 */
public interface PrimitiveLongView {
    /**
     * 返回当前可读取的 primitive 元素数。
     */
    int primitiveSize();
    /**
     * 按从零开始的顺序下标读取一个 long，越界及生命周期检查由具体实现负责。
     * @param index 本视图有效范围内从零开始的顺序下标
     */
    long primitiveValueAt(int index);
}

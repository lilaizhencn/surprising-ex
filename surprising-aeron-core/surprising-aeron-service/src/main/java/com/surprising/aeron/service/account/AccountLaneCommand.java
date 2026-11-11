package com.surprising.aeron.service.account;

/** 可投递到账户线程的任务契约。任务先声明输入就绪，再在账户实际拥有者线程执行。 */
public interface AccountLaneCommand {

    /** 在本工作线程拥有账户时执行一条已就绪任务，完成交接由任务自身负责。 */
    void execute(AccountPartition lane);

    /** 判断普通或准入任务需要的账本输入是否已到齐，默认就绪。 */
    default boolean accountReady() { return true; }
}

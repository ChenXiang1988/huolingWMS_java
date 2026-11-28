package com.huoling.wms.masterdata.owner.domain;

/**
 * 货主编号来源（OI-01：编号由系统生成、全局唯一、不可变）。
 *
 * <p>编号必须由序列产生：多副本并发建档时，应用侧自增或随机都会撞号或跳号，
 * 唯一索引只能事后拦住，不能保证「连续且不重复」。实现取自
 * {@code 'OW' || to_char(nextval('seq_wms_owner_no'), 'FM000000')}，
 * 与 Kernel 的 {@code OwnerNumberRule} 同形态。
 */
public interface OwnerNumberSource {

    /** 取下一个货主编号，形如 {@code OW000001}；序号超过六位自然变长。 */
    String nextOwnerNo();
}

package com.huoling.wms.masterdata.owner.infrastructure;

import com.huoling.wms.masterdata.owner.domain.OwnerNumberSource;
import com.huoling.wms.masterdata.owner.infrastructure.persistence.OwnerArchiveMapper;
import org.springframework.stereotype.Component;

/**
 * 货主编号来源实现：直接取库里的序列，多副本并发建档也不会撞号。
 *
 * <p>为什么不在应用侧自增或随机：编号是对外承诺「全局唯一且不可变」的标识（OI-01），
 * 唯一索引只能事后拦截，撞号会让一笔本来合法的建档失败；序列是唯一能保证「不重不跳」的来源。
 */
@Component
public class OwnerNumberSourceImpl implements OwnerNumberSource {

    private final OwnerArchiveMapper mapper;

    public OwnerNumberSourceImpl(OwnerArchiveMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String nextOwnerNo() {
        return mapper.nextOwnerNo();
    }
}

package com.itzixi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.itzixi.bean.ChatRecord;
import org.apache.ibatis.annotations.Param;
//com/itzixi/mapper/ChatRecordMapper.java
import java.util.List;
/**
 * @ClassName ChatRecordMapper
 * @Author 
 * @Version 1.0
 * @Description ChatRecordMapper
 **/
public interface ChatRecordMapper extends BaseMapper<ChatRecord> {

    /** 取某用户最近 limit 条，按 message_seq 倒序（数据库侧 LIMIT） */
    List<ChatRecord> selectRecentByMember(@Param("familyMember") String familyMember, @Param("limit") int limit);

    /** 取该用户当前最大 message_seq */
    int selectMaxSeqByMember(@Param("familyMember") String familyMember);
}

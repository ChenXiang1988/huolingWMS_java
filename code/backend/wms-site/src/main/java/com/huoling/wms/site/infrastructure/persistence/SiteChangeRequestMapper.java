package com.huoling.wms.site.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 单仓变更申请读写。
 *
 * <p>状态与上行进度分开更新：业务状态只由下行审核结果改，上行进度只由投递器改，
 * 两条路径互不覆盖，避免「上行重试把已审核的申请改回待审核」。
 */
@Mapper
public interface SiteChangeRequestMapper {

    String COLUMNS = """
        id, request_no as "requestNo", accept_no as "acceptNo", site_id as "siteId",
        archive_id as "archiveId", archive_version as "archiveVersion", owner_id as "ownerId",
        change_field as "changeField", target_value as "targetValue", reason, status,
        review_reason as "reviewReason", archive_reviewed_version as "archiveReviewedVersion",
        last_event_id as "lastEventId", upload_status as "uploadStatus",
        upload_attempts as "uploadAttempts", last_error as "lastError",
        next_retry_at as "nextRetryAt", created_at as "createdAt", updated_at as "updatedAt"
        """;

    @Select("select count(1) from site_archive_change_request"
        + " where site_id = #{siteId} and archive_id = #{archiveId} and status = 'PENDING'")
    int countPending(@Param("siteId") Long siteId, @Param("archiveId") String archiveId);

    @Insert("""
        insert into site_archive_change_request (id, request_no, accept_no, site_id, archive_id,
            archive_version, owner_id, change_field, target_value, reason, status, review_reason,
            archive_reviewed_version, last_event_id, upload_status, upload_attempts, last_error,
            next_retry_at, created_at, updated_at)
        values (#{s.id}, #{s.requestNo}, #{s.acceptNo}, #{s.siteId}, #{s.archiveId},
            #{s.archiveVersion}, #{s.ownerId}, #{s.changeField}, #{s.targetValue}, #{s.reason},
            #{s.status}, #{s.reviewReason}, #{s.archiveReviewedVersion}, #{s.lastEventId},
            #{s.uploadStatus}, #{s.uploadAttempts}, #{s.lastError}, #{s.nextRetryAt},
            #{s.createdAt}, #{s.updatedAt})
        """)
    int insert(@Param("s") Map<String, Object> request);

    /**
     * 本仓申请列表：排序与游标比较都按申请字段落列（{@code updateTime} 落申请创建时间，
     * {@code ownerCode} 落归属货主，{@code name} 落变更字段），主键恒为次级键。
     *
     * <p>列名由 {@code sortKey} 的闭集分支给出，不拼接外部串；排序与游标比较同列同方向，
     * 同值行靠主键继续前进，翻页不重不漏。
     */
    @Select("""
        <script>
        select""" + " " + COLUMNS + """
          from site_archive_change_request
         where site_id = #{siteId}
        <if test="status != null and status != ''">
           and status = #{status}
        </if>
        <if test="requestNo != null and requestNo != ''">
           and request_no = #{requestNo}
        </if>
        <if test="archiveId != null and archiveId != ''">
           and archive_id = #{archiveId}
        </if>
        <if test="cursorId != null">
          <choose>
            <when test="sortKey == 'OWNER_CODE'">
              <choose>
                <when test="sortAsc">
           and (owner_id &gt; #{cursorNumber} or (owner_id = #{cursorNumber} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (owner_id &lt; #{cursorNumber} or (owner_id = #{cursorNumber} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </when>
            <when test="sortKey == 'NAME'">
              <choose>
                <when test="sortAsc">
           and (change_field &gt; #{cursorText}
                or (change_field = #{cursorText} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (change_field &lt; #{cursorText}
                or (change_field = #{cursorText} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </when>
            <otherwise>
              <choose>
                <when test="sortAsc">
           and (created_at &gt; #{cursorTime} or (created_at = #{cursorTime} and id &gt; #{cursorId}))
                </when>
                <otherwise>
           and (created_at &lt; #{cursorTime} or (created_at = #{cursorTime} and id &lt; #{cursorId}))
                </otherwise>
              </choose>
            </otherwise>
          </choose>
        </if>
         order by
        <choose>
          <when test="sortKey == 'OWNER_CODE'">owner_id</when>
          <when test="sortKey == 'NAME'">change_field</when>
          <otherwise>created_at</otherwise>
        </choose>
        <choose>
          <when test="sortAsc">asc, id asc</when>
          <otherwise>desc, id desc</otherwise>
        </choose>
         limit #{limit}
        </script>
        """)
    List<Map<String, Object>> page(@Param("siteId") Long siteId,
                                   @Param("status") String status,
                                   @Param("requestNo") String requestNo,
                                   @Param("archiveId") String archiveId,
                                   @Param("sortKey") String sortKey,
                                   @Param("sortAsc") boolean sortAsc,
                                   @Param("cursorId") Long cursorId,
                                   @Param("cursorTime") OffsetDateTime cursorTime,
                                   @Param("cursorNumber") Long cursorNumber,
                                   @Param("cursorText") String cursorText,
                                   @Param("limit") int limit);

    @Select("""
        select""" + " " + COLUMNS + """
          from site_archive_change_request
         where site_id = #{siteId}
           and request_no = #{requestNo}
        """)
    Map<String, Object> findByRequestNo(@Param("siteId") Long siteId,
                                        @Param("requestNo") String requestNo);

    @Select("""
        select""" + " " + COLUMNS + """
          from site_archive_change_request
         where upload_status in ('PENDING', 'FAILED')
           and (next_retry_at is null or next_retry_at <= #{now})
         order by created_at asc
         limit #{limit}
        """)
    List<Map<String, Object>> findUploadable(@Param("limit") int limit,
                                             @Param("now") OffsetDateTime now);

    @Update("""
        update site_archive_change_request
           set upload_status = 'UPLOADED',
               upload_attempts = upload_attempts + 1,
               last_error = null,
               next_retry_at = null,
               updated_at = #{now}
         where request_no = #{requestNo}
        """)
    int markUploaded(@Param("requestNo") String requestNo, @Param("now") OffsetDateTime now);

    @Update("""
        update site_archive_change_request
           set upload_status = 'FAILED',
               upload_attempts = upload_attempts + 1,
               last_error = #{error},
               next_retry_at = #{now} + make_interval(secs => least(power(2, upload_attempts), 64) * 30),
               updated_at = #{now}
         where request_no = #{requestNo}
        """)
    int markUploadFailed(@Param("requestNo") String requestNo,
                         @Param("error") String error,
                         @Param("now") OffsetDateTime now);

    @Update("""
        update site_archive_change_request
           set status = #{status},
               review_reason = #{reviewReason},
               archive_reviewed_version = #{archiveReviewedVersion},
               last_event_id = #{eventId},
               updated_at = #{now}
         where request_no = #{requestNo}
           and status = 'PENDING'
           and (last_event_id is null or last_event_id &lt;&gt; #{eventId})
        """)
    int applyReviewResult(@Param("requestNo") String requestNo,
                          @Param("status") String status,
                          @Param("reviewReason") String reviewReason,
                          @Param("archiveReviewedVersion") Long archiveReviewedVersion,
                          @Param("eventId") String eventId,
                          @Param("now") OffsetDateTime now);
}

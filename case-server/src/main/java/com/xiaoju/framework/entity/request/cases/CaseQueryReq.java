package com.xiaoju.framework.entity.request.cases;

import com.xiaoju.framework.constants.SystemConstant;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * case相关的查询体
 *
 * @author hcy
 * @date 2020/8/12
 */
@Data
public class CaseQueryReq {

    private Long id;

    private Integer caseType;

    private Long lineId;

    private String title;

    private String creator;

    private String requirementId;

    private String beginTime;

    private String endTime;

    private Integer channel;

    private List<Long> reqIds;

    private String bizId;

    private Integer pageNum;

    private Integer pageSize;

    public CaseQueryReq(Long id, Integer caseType, Long lineId, String title, String creator, String requirementId, String beginTime, String endTime, Integer channel, Integer pageNum, Integer pageSize) {
        this.id = id;
        this.caseType = caseType;
        this.lineId = lineId;
        this.title = title;
        this.creator = creator;
        this.requirementId = requirementId;
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.channel = channel;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public CaseQueryReq(Long id, Long lineId, String creator, String requirementId, String beginTime, String endTime, Integer channel) {
        this.id = id;
        this.lineId = lineId;
        this.creator = creator;
        this.requirementId = requirementId;
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.channel = channel;
    }

    public CaseQueryReq(Long lineId, Integer channel, String[] reqIds, Integer pageNum, Integer pageSize) {
        // String[]转为List<Long>
        List<Long> reqIdList = new ArrayList<>();
        for (String reqId : reqIds) {
            reqIdList.add(Long.valueOf(reqId));
        }
        this.lineId = lineId;
        this.channel = channel;
        this.reqIds = reqIdList;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public CaseQueryReq(Integer caseType, String title, String creator, String reqIds, String beginTime, String endTime, Integer channel, String bizId, Long lineId, Integer pageNum, Integer pageSize) {
        this.caseType = caseType;
        this.title = title;
        this.creator = creator;
        this.reqIds= convert(reqIds);
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.channel = channel;
        this.bizId = bizId;
        this.lineId = lineId;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    private List<Long> convert(String str) {
        List<Long> res = new ArrayList<>();
        if (!StringUtils.isEmpty(str)) {
            for (String reqId : str.split(SystemConstant.COMMA)) {
               res.add(Long.valueOf(reqId));
            }
        }
        return res;
    }
}

package com.xiaoju.framework.entity.dto;

import lombok.Data;

import java.util.Date;

/**
 * @author hcy
 * @date 2020/10/29
 */
@Data
public class RecordWsDto {

    private Date updateTime;

    private String executors;

    private Integer env;

    private String caseContent;

    private String chooseContent;
}

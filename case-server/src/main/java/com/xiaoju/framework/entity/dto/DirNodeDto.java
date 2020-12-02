package com.xiaoju.framework.entity.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author ldl
 * @version: 1.0.0
 * @description:
 * @date 2020/9/9 3:45 下午
 */

@Data
public class DirNodeDto {

    private String id;
    private String text;
    private String parentId;
    private Set<String> caseIds = new HashSet<>();

    private List<DirNodeDto> children = new ArrayList<>();
}

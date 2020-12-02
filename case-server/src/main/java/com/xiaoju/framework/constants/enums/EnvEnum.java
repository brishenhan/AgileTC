package com.xiaoju.framework.constants.enums;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行任务环境枚举
 * TODO好像可以删掉
 *
 * @author didi
 * @date 2020/9/24
 */
public enum EnvEnum {
    // 枚举
    TestEnv(0, "测试环境"),
    PreEnv(1, "预发环境"),
    OnlineEnv(2, "线上环境"),
    TestQaEnv(3, "冒烟qa"),
    TestRdEnv(4, "冒烟rd"),
    SourceEnv(10, "原始用例模式"),
    SmkEnv(20, "冒烟用例"),
    CommonEnv(100, "脑图通用场景用法");

    private Integer value;
    private String name;

    EnvEnum(Integer value, String name) {
        this.value = value;
        this.name = name;
    }

    public Integer getValue() {
        return value;
    }

    public String getName() {
        return name;
    }
}

package com.xiaoju.framework.entity.request.cases;

import com.xiaoju.framework.entity.request.ParamValidate;
import lombok.Data;

/**
 * 逻辑删除某条用例集
 *
 * @author hcy
 * @date 2020/9/7
 */
@Data
public class CaseDeleteReq implements ParamValidate {

    private Long id;

    @Override
    public void validate() {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("用例id为空");
        }
    }
}

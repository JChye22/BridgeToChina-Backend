package com.btchina.content.model.form;

import com.btchina.core.api.PageQueryParam;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
@ApiModel(description = "问题搜索")
public class QuestionSearchForm extends PageQueryParam {
    @ApiModelProperty(required = true ,value = "关键字",example = "测试")
    public String keyword;
}

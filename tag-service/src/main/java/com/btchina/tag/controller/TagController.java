package com.btchina.tag.controller;


import com.btchina.core.api.CommonResult;
import com.btchina.tag.model.form.AddTagForm;
import com.btchina.tag.service.TagService;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 标签表 前端控制器
 * </p>
 *
 * @author franky
 * @since 2023-02-06
 */
@RestController
@RequestMapping("/tag")
public class TagController {

    @Autowired
    private TagService tagService;

    @ApiOperation(value = "添加标签")
    @PostMapping("/add")
    public CommonResult<Void> addTag(@Validated @RequestBody AddTagForm addTagForm) {
        Boolean isSuccess = tagService.addTag(addTagForm);
        if (!isSuccess) {
            return CommonResult.failed();
        }
        // 标题 内容 标签
        return CommonResult.success(null);
    }

}


package com.btchina.message.controller;


import com.btchina.core.api.CommonResult;
import com.btchina.core.api.PageQueryParam;
import com.btchina.core.api.PageResult;
import com.btchina.core.util.AuthHelper;
import com.btchina.message.entity.Dialog;
import com.btchina.message.model.form.DialogAddForm;
import com.btchina.message.model.vo.DialogVO;
import com.btchina.message.service.DialogService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 会话详情表 前端控制器
 * </p>
 *
 * @author franky
 * @since 2023-03-30
 */
@RestController
@Api(tags = "会话模块")
@RequestMapping("/message/dialog/")
public class DialogController {

    @Autowired
    private DialogService dialogService;

    @ApiOperation("添加会话")
    @PostMapping("add")
    public CommonResult<DialogVO> add(@Validated @RequestBody DialogAddForm dialogAddForm) {
        Long userId = AuthHelper.getUserId();
        DialogVO result= dialogService.add(userId, dialogAddForm);
        return CommonResult.success(result);
    }

    @ApiOperation("获取会话列表")
    @PostMapping("list")
    public CommonResult<PageResult<DialogVO>> list(@Validated @RequestBody PageQueryParam pageQueryParam) {
        Long userId = AuthHelper.getUserId();
        PageResult<DialogVO> dialogVOList = dialogService.getList(userId, pageQueryParam);
        return CommonResult.success(dialogVOList);
    }

}


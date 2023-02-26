package com.btchina.answer.service;

import com.btchina.answer.entity.Answer;
import com.baomidou.mybatisplus.extension.service.IService;
import com.btchina.core.api.DeleteForm;
import com.btchina.core.api.PageResult;
import com.btchina.model.form.answer.AddAnswerForm;
import com.btchina.model.form.answer.QueryAnswerForm;
import com.btchina.model.form.answer.UpdateAnswerForm;
import com.btchina.model.vo.answer.AnswerVO;

/**
 * <p>
 * 回答表 服务类
 * </p>
 *
 * @author franky
 * @since 2023-02-25
 */
public interface AnswerService extends IService<Answer> {

    Boolean addAnswer(AddAnswerForm addAnswerForm, Long userId);

    Boolean delAnswer(DeleteForm deleteForm, Long userId);

    Boolean updateAnswer(UpdateAnswerForm updateAnswerForm, Long userId);

    PageResult<AnswerVO> queryAnswer(QueryAnswerForm queryAnswerForm);
}

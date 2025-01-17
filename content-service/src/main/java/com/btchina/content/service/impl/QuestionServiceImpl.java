package com.btchina.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.btchina.content.service.*;
import com.btchina.core.api.DeleteForm;
import com.btchina.core.api.PageResult;
import com.btchina.core.api.ResultCode;
import com.btchina.core.exception.GlobalException;
import com.btchina.entity.User;
import com.btchina.feign.clients.QuestionClient;
import com.btchina.feign.clients.TagClient;
import com.btchina.feign.clients.UserClient;
import com.btchina.model.form.tag.EditQuestionTagForm;
import com.btchina.model.vo.answer.AnswerVO;
import com.btchina.content.constant.QuestionConstant;
import com.btchina.content.entity.Answer;
import com.btchina.content.entity.Question;
import com.btchina.content.entity.QuestionUserFavorite;
import com.btchina.content.entity.QuestionUserLike;
import com.btchina.content.mapper.QuestionMapper;
import com.btchina.content.mapper.es.QuestionRepository;
import com.btchina.content.model.doc.QuestionDoc;
import com.btchina.content.model.enums.QueryTypeEnum;
import com.btchina.content.model.form.*;
import com.btchina.model.vo.question.QuestionVO;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FieldValueFactorFunction;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.functionscore.FieldValueFactorFunctionBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.jetbrains.annotations.NotNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.elasticsearch.core.query.UpdateResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 问答表 服务实现类
 * </p>
 *
 * @author franky
 * @since 2023-02-01
 */
@Service
@Slf4j
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private TagClient tagClient;

    @Autowired
    private UserClient userClient;

    @Autowired
    private QuestionClient questionClient;

    @Autowired
    ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Autowired
    @Lazy
    private QuestionUserLikeService questionUserLikeService;

    @Autowired
    @Lazy
    private QuestionUserFavoriteService questionUserFavoriteService;

    @Autowired
    private AnswerService answerService;

    @Autowired
    private TagService tagService;

    @Value("${secret.openai}")
    private String openAiSecret;
    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    public Boolean addQuestion(AddQuestionForm addQuestionForm, Long userId) {
        if (userId == null) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        // 1. 添加问题
        Random r = new Random();
        Question question = new Question();
        question.setTitle(addQuestionForm.getTitle());
        question.setContent(addQuestionForm.getContent());
        question.setUserId(userId);
        question.setFavoriteCount(0);
        question.setViewCount(0);
        question.setAnswerCount(0);
        question.setIsPublic(true);
        question.setLikeCount(r.nextInt(1000));
        if (addQuestionForm.getImages() != null) {
            String images = String.join(",", addQuestionForm.getImages());
            question.setImages(images);
        }
        Boolean isSuccess = this.baseMapper.insert(question) > 0;

        // 2. 添加标签
        AddTagForm addTagForm = new AddTagForm();
        addTagForm.setId(question.getId());
        addTagForm.setTags(addQuestionForm.getTags());
        tagService.addTag(addTagForm);

        // 3. 添加es文档
        QuestionDoc questionDoc = new QuestionDoc();
        BeanUtils.copyProperties(question, questionDoc);
        String tagString = String.join(",", addQuestionForm.getTags());
        questionDoc.setTags(tagString);
        // 3. 添加es文档
        if (isSuccess) {
            rabbitTemplate.convertAndSend(QuestionConstant.EXCHANGE_NAME, QuestionConstant.INSERT_KEY, questionDoc);
        }
        return isSuccess;
    }


    /**
     * 增加到es文档
     *
     * @param questionDoc
     * @return
     */
    @Override
    public void addEsDoc(QuestionDoc questionDoc) {
        try {
            QuestionDoc question = questionRepository.save(questionDoc);
            log.info("增加es文档成功: {} ", question);
        } catch (Exception e) {
            log.error("增加es文档失败: {} ", e.getMessage(), e);
        }
    }

    /**
     * 更新es文档
     *
     * @param questionDoc
     * @return
     */
    @Override
    public void updateEsDoc(QuestionDoc questionDoc) {
        try {
            QuestionDoc question = questionRepository.save(questionDoc);
            log.info("更新es文档成功: {} ", question);
        } catch (Exception e) {
            log.error("更新es文档失败: {} ", e.getMessage(), e);
        }
    }

    @Override
    public Boolean deleteQuestion(Long questionId, Long selfId) {
        if (selfId == null) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        Question question = this.baseMapper.selectById(questionId);

        if (question == null) {
            throw GlobalException.from("问题不存在");
        }

        if (!question.getUserId().equals(selfId)) {
            throw GlobalException.from("无权限删除");
        }
        // 1. 删除问题
        Boolean isSuccess = this.baseMapper.deleteById(questionId) > 0;
        DeleteForm deleteForm = new DeleteForm();
        deleteForm.setId(questionId);
        // 2. 删除标签与问题的关联
        tagClient.deleteTag(deleteForm);
        // 3. 删除es文档
        if (isSuccess) {
            rabbitTemplate.convertAndSend(QuestionConstant.EXCHANGE_NAME, QuestionConstant.DELETE_KEY, questionId);
        }
        return isSuccess;
    }

    @Override
    public void deleteEsDoc(Long id) {
        try {
            questionRepository.deleteById(id);
            log.info("删除es文档成功: {} ", id);
        } catch (Exception e) {
            log.error("删除es文档失败: {} ", e.getMessage(), e);
        }
    }

    @Override
    public void updateFieldEsDoc(Long id, String field, Object value) {
        try {
            Document document = Document.create();
            document.put(field, value);
            UpdateResponse response = elasticsearchRestTemplate.update(UpdateQuery.builder(id.toString()).withDocument(document).build(), IndexCoordinates.of(QuestionConstant.INDEX));
            System.out.println("response = " + response);
            log.info("更新es文档成功: {} ", id);
        } catch (Exception e) {
            log.error("更新es文档失败: {} ", e.getMessage(), e);
        }
    }

    @Override
    public QuestionDoc getEsDoc(Long id) {
        return questionRepository.findById(id.toString());
    }

    @Override
    public Boolean editQuestion(EditQuestionForm editQuestionForm, Long selfId) {
        if (selfId == null) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        QuestionDoc questionDoc = questionRepository.findById(editQuestionForm.getId().toString());
        if (questionDoc == null) {
            throw GlobalException.from("问题不存在");
        }
        if (!questionDoc.getUserId().equals(selfId)) {
            throw GlobalException.from("无权限编辑");
        }
        questionDoc.setTitle(editQuestionForm.getTitle());
        questionDoc.setContent(editQuestionForm.getContent());
        String tagStr = String.join(",", editQuestionForm.getTags());
        if (editQuestionForm.getImages() != null) {
            String images = String.join(",", editQuestionForm.getImages());
            questionDoc.setImages(images);
        }
        questionDoc.setIsPublic(editQuestionForm.getIsPublic());
        questionDoc.setTags(tagStr);
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        Boolean isSuccess = this.baseMapper.updateById(question) > 0;

        // 1. 更新标签
        EditQuestionTagForm editQuestionTagForm = new EditQuestionTagForm();
        editQuestionTagForm.setId(editQuestionForm.getId());
        editQuestionTagForm.setTags(editQuestionForm.getTags());
        tagClient.editQuestionTags(editQuestionTagForm);

        // 2. 更新es文档
        if (isSuccess) {
            rabbitTemplate.convertAndSend(QuestionConstant.EXCHANGE_NAME, QuestionConstant.UPDATE_KEY, questionDoc);
        }
        return isSuccess;
    }

    /**
     * 增加问题的回答数
     *
     * @param questionId
     */
    @Override
    public void increaseAnswerCount(Long questionId) {
        QuestionDoc questionDoc = getEsDoc(questionId);
        if (questionDoc == null) {
            return;
        }
        if (questionDoc.getAnswerCount() == null) {
            questionDoc.setAnswerCount(0);
        } else {
            questionDoc.setAnswerCount(questionDoc.getAnswerCount() + 1);
        }
        updateEsDoc(questionDoc);
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        this.baseMapper.updateById(question);
    }

    /**
     * 减少问题的回答数
     *
     * @param questionId
     */
    @Override
    public void decreaseAnswerCount(Long questionId) {
        QuestionDoc questionDoc = getEsDoc(questionId);
        if (questionDoc == null) {
            return;
        }
        if (questionDoc.getAnswerCount() == null) {
            questionDoc.setAnswerCount(0);
        } else {
            questionDoc.setAnswerCount(questionDoc.getAnswerCount() - 1);
        }
        updateEsDoc(questionDoc);
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        this.baseMapper.updateById(question);
    }

    @Override
    public Boolean setBestAnswer(QuestionSetAnswerForm questionSetAnswerForm, Long userId) {
        if (userId == null) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        QuestionDoc questionDoc = getEsDoc(questionSetAnswerForm.getQuestionId());
        if (questionDoc == null) {
            throw GlobalException.from("问题不存在");
        }
        if (!questionDoc.getUserId().equals(userId)) {
            throw GlobalException.from("无权限设置最佳答案");
        }
        Answer answer = answerService.getById(questionSetAnswerForm.getAnswerId());
        if (answer == null) {
            throw GlobalException.from("回答不存在");
        }
        //有设置过答案就把之前的答案设置为空
        if (questionDoc.getBestAnswerId() != null) {
            Answer oldAnswer = answerService.getById(questionDoc.getBestAnswerId());
            if (oldAnswer != null) {
                oldAnswer.setIsBest(0);
                answerService.updateById(oldAnswer);
            }
        }
        //设置新的答案
        answer.setIsBest(1);
        answerService.updateById(answer);

        // 1. 设置最佳答案
        questionDoc.setBestAnswerId(questionSetAnswerForm.getAnswerId());
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);

        Boolean isSuccess = this.baseMapper.updateById(question) > 0;
        // 2. 更新es文档
        if (isSuccess) {
            rabbitTemplate.convertAndSend(QuestionConstant.EXCHANGE_NAME, QuestionConstant.UPDATE_KEY, questionDoc);
        }


        return isSuccess;
    }

    @Override
    public QuestionVO getVObyId(Long id, Long userId) {
        QuestionVO questionVO = new QuestionVO();
        QuestionDoc questionDoc = getEsDoc(id);
        if (questionDoc == null) {
            throw GlobalException.from("问题不存在");
        }
        User user = userClient.findById(questionDoc.getUserId());
        BeanUtils.copyProperties(questionDoc, questionVO);
        //装换标签和图片
        if (questionDoc.getTags() != null) {
            List<String> tags = Arrays.asList(questionDoc.getTags().split(","));
            questionVO.setTags(tags);
        }
        if (questionDoc.getImages() != null) {
            List<String> images = Arrays.asList(questionDoc.getImages().split(","));
            questionVO.setImages(images);
        }

        LambdaQueryWrapper<QuestionUserLike> questionUserLikeWrapper = new LambdaQueryWrapper<QuestionUserLike>();
        if (userId != null) {
            questionUserLikeWrapper.eq(QuestionUserLike::getQuestionId, id);
            questionUserLikeWrapper.eq(QuestionUserLike::getUserId, userId);
            QuestionUserLike userLike = questionUserLikeService.getOne(questionUserLikeWrapper);
            if (userLike != null) {
                questionVO.setLikeStatus(userLike.getStatus());
            }
        }
        questionVO.setAvatar(user.getAvatar());
        questionVO.setNickname(user.getNickname());
        return questionVO;
    }

    @Override
    public PageResult<QuestionVO> searchQuestion(QuestionSearchForm questionSearchForm, Long selfId) {
        SearchHits<QuestionDoc> result = searchByEs(questionSearchForm.getKeyword(), questionSearchForm.getCurrentPage(), questionSearchForm.getPageSize());
        List<QuestionVO> questionVOList = convertSearchHits(result, selfId, false);
        return getQuestionVOPageResult(result, questionVOList, questionSearchForm.getCurrentPage(), questionSearchForm.getPageSize());
    }

    @Override
    public PageResult<QuestionVO> relatedQuestion(QuestionRelatedForm questionRelatedForm) {
        SearchHits<QuestionDoc> result = searchByEs(questionRelatedForm.getKeyword(), questionRelatedForm.getCurrentPage(), questionRelatedForm.getPageSize());
        List<QuestionVO> questionVOList = convertSearchHits(result, null, true);
        return getQuestionVOPageResult(result, questionVOList, questionRelatedForm.getCurrentPage(), questionRelatedForm.getPageSize());
    }

    @Override
    public Map<Long, QuestionVO> findByIds(List<Long> ids) {
        Map<Long, QuestionVO> questionVOMap = new HashMap<>();
        if (ids == null || ids.size() == 0) {
            return questionVOMap;
        }
        List<QuestionDoc> questionDocList = getEsDocByIds(ids);
        if (questionDocList != null && questionDocList.size() > 0) {
            questionDocList.stream().map(questionDoc -> {
                QuestionVO questionVO = new QuestionVO();
                BeanUtils.copyProperties(questionDoc, questionVO);
                questionVOMap.put(questionDoc.getId(), questionVO);
                return questionVO;
            }).collect(Collectors.toList());
        }
        return questionVOMap;
    }

    private List<QuestionDoc> getEsDocByIds(List<Long> ids) {
        List<String> idStrList = ids.stream().map(Object::toString).collect(Collectors.toList());
        log.info("idStrList:{}", idStrList);

        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(QueryBuilders.termsQuery("id", idStrList))
                .build();
        return elasticsearchRestTemplate.search(query, QuestionDoc.class).stream().map(SearchHit::getContent).collect(Collectors.toList());
        //return questionRepository.findAllByIdIn(idStrList);
    }


    @NotNull
    private PageResult<QuestionVO> getQuestionVOPageResult(SearchHits<QuestionDoc> result, List<QuestionVO> questionVOList, Integer currentPage, Integer pageSize) {
        PageResult<QuestionVO> pageResult = new PageResult<>();
        pageResult.setTotal(result.getTotalHits());
        pageResult.setList(questionVOList);
        pageResult.setCurrentPage(currentPage);
        pageResult.setPageSize(pageSize);
        pageResult.setTotalPage((int) Math.ceil((double) result.getTotalHits() / pageSize));
        return pageResult;
    }


    public SearchHits<QuestionDoc> searchByEs(String keyword, Integer currentPage, Integer pageSize) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.must().add(QueryBuilders.multiMatchQuery(keyword, "searchContent", "title", "content"));
        Pageable page = PageRequest.of(currentPage - 1, pageSize);
        //指定多个field
        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(boolQueryBuilder)
                .withPageable(page)
                .withSort(SortBuilders.fieldSort("_score").order(SortOrder.DESC))
                .build();
        //3.执行查询
        return elasticsearchRestTemplate.search(query, QuestionDoc.class);
    }

    @Override
    public void increaseFavouriteCount(Long questionId) {
        QuestionDoc questionDoc = getEsDoc(questionId);
        if (questionDoc == null) {
            return;
        }
        if (questionDoc.getFavoriteCount() == null) {
            questionDoc.setFavoriteCount(0);
        } else {
            questionDoc.setFavoriteCount(questionDoc.getFavoriteCount() + 1);
        }
        updateEsDoc(questionDoc);
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        this.baseMapper.updateById(question);
    }

    public void decreaseFavouriteCount(Long questionId) {
        QuestionDoc questionDoc = getEsDoc(questionId);
        if (questionDoc == null) {
            return;
        }
        if (questionDoc.getFavoriteCount() == null) {
            questionDoc.setFavoriteCount(0);
        } else {
            questionDoc.setFavoriteCount(questionDoc.getFavoriteCount() - 1);
        }
        updateEsDoc(questionDoc);
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        this.baseMapper.updateById(question);
    }

    @Override
    public Boolean addView(Long id) {
        QuestionDoc questionDoc = getEsDoc(id);
        if (questionDoc == null) {
            throw GlobalException.from(ResultCode.QUESTION_NOT_EXIST);
        }
        if (questionDoc.getViewCount() == null) {
            questionDoc.setViewCount(0);
        } else {
            questionDoc.setViewCount(questionDoc.getViewCount() + 1);
        }
        updateEsDoc(questionDoc);
        updateQuestion(questionDoc);
        return true;
    }

    @Override
    public Boolean cancelBestAnswer(QuestionSetAnswerForm questionSetAnswerForm, Long userId) {
        if (userId == null) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        QuestionDoc questionDoc = getEsDoc(questionSetAnswerForm.getQuestionId());
        if (questionDoc == null) {
            throw GlobalException.from(ResultCode.QUESTION_NOT_EXIST);
        }
        if (!questionDoc.getUserId().equals(userId)) {
            throw GlobalException.from(ResultCode.UNAUTHORIZED);
        }
        Answer answer = answerService.getById(questionDoc.getBestAnswerId());
        if (answer == null) {
            throw GlobalException.from(ResultCode.ANSWER_NOT_EXIST);
        }
        answer.setIsBest(0);
        answerService.updateById(answer);
        questionDoc.setBestAnswerId(null);
        updateEsDoc(questionDoc);
        updateQuestion(questionDoc);
        return true;
    }

    @Override
    public String generateTitle(String text) {
        String API_KEY = openAiSecret;
        String prompt = "请根据以下内容生成一个问题标题：\n" + text + "\n标题格式：#问题标题#";
        String model = "text-davinci-003";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("max_tokens", 100);
        requestBody.put("n", 1);
        requestBody.put("temperature", 0.5);
        RequestBody body = RequestBody.create(JSON, new Gson().toJson(requestBody));
        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/completions")
                .post(body)
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .build();
        String responseStr = "";
        try {
            Response response = client.newCall(request).execute();
            responseStr = response.body().string();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return responseStr;
    }


    public void updateQuestion(QuestionDoc questionDoc) {
        Question question = new Question();
        BeanUtils.copyProperties(questionDoc, question);
        this.baseMapper.updateById(question);
    }


    @Override
    public SearchHits<QuestionDoc> queryEsQuestion(QuestionQueryForm questionQueryForm, Long selfId) {
        QueryTypeEnum queryTypeEnum = QueryTypeEnum.getQueryTypeEnum(questionQueryForm.getType());
        switch (queryTypeEnum) {
            case HOT:
                //QueryBuilder queryBuilder = QueryBuilders.matchAllQuery();
                //FunctionScoreQueryBuilder.FilterFunctionBuilder[] filterFunctionBuilders = new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{
                //        new FunctionScoreQueryBuilder.FilterFunctionBuilder(QueryBuilders.termQuery("type", 3), ScoreFunctionBuilders.weightFactorFunction(100)),
                //        new FunctionScoreQueryBuilder.FilterFunctionBuilder(QueryBuilders.termQuery("type", 2), ScoreFunctionBuilders.weightFactorFunction(1)),
                //        new FunctionScoreQueryBuilder.FilterFunctionBuilder(QueryBuilders.matchQuery("type", 1), ScoreFunctionBuilders.weightFactorFunction(1))
                //};
                BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
                //ScoreFunctionBuilder<?> scoreFunctionBuilder = ScoreFunctionBuilders.fieldValueFactorFunction("likeCount").modifier(FieldValueFactorFunction.Modifier.LN1P).factor(0.1f);
                //FunctionScoreQueryBuilder query = QueryBuilders.functionScoreQuery(boolQueryBuilder,scoreFunctionBuilder).boostMode(CombineFunction.SUM);

                FieldValueFactorFunctionBuilder fieldQuery = new FieldValueFactorFunctionBuilder(
                        "likeCount");
                // 额外分数=log(1+score)
                fieldQuery.factor(0.1f);
                fieldQuery.modifier(FieldValueFactorFunction.Modifier.LOG1P);
                // 最终分数=_score+额外分数
                FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders
                        .functionScoreQuery(boolQueryBuilder, fieldQuery)
                        .boostMode(CombineFunction.SUM);
                //根据分值倒序排列

                boolQueryBuilder.must().add(QueryBuilders.multiMatchQuery("测试", "title", "content"));
                boolQueryBuilder.must().add(QueryBuilders.rangeQuery("createdTime").gte(1675853174178L));
                //boolQueryBuilder.must().add(scoreFunctionBuilder);
                long now = System.currentTimeMillis() / 1000L;
                long daySecond = 60 * 60 * 24;
                long dayTime = now - (now + 8 * 3600) % daySecond;
                log.info("dayTime:{}", dayTime);
                //指定多个field
                NativeSearchQuery query1 = new NativeSearchQueryBuilder()
                        //.withQuery(queryBuilder)
                        .withQuery(functionScoreQueryBuilder)
                        //.withPageable(page)
                        //排序
                        .withSort(SortBuilders.fieldSort("_score").order(SortOrder.DESC))
                        //.withSort(sort)
                        .build();
                //3.执行查询
                SearchHits<QuestionDoc> result = elasticsearchRestTemplate.search(query1, QuestionDoc.class);
                return result;
            //查询对象
            case MY:
                log.info("查询我的问题");
                break;
        }
        return null;

    }

    @Override
    public PageResult<QuestionVO> queryQuestion(QuestionQueryForm questionQueryForm, Long selfId, Boolean isSeo) {
        QueryTypeEnum queryTypeEnum = QueryTypeEnum.getQueryTypeEnum(questionQueryForm.getType());
        switch (queryTypeEnum) {
            case HOT:
                log.info("查询热门问题");
                return queryHotQuestion(questionQueryForm, selfId, isSeo);
            case NEW:
                log.info("查询最新问题");
                return queryNewQuestion(questionQueryForm, selfId, isSeo);
        }
        return null;
    }

    public PageResult<QuestionVO> queryHotQuestion(QuestionQueryForm questionQueryForm, Long selfId, Boolean isSeo) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        FieldValueFactorFunctionBuilder fieldQuery = new FieldValueFactorFunctionBuilder("likeCount")
                .modifier(FieldValueFactorFunction.Modifier.LOG1P)
                .factor(0.1f);

        // 最终分数=_score+额外分数
        FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders
                .functionScoreQuery(boolQueryBuilder, fieldQuery)
                .boostMode(CombineFunction.SUM);

        if (questionQueryForm.getDate() != null) {
            RangeQueryBuilder timeRangeQuery = QueryBuilders.rangeQuery("createdTime")
                    //.gte(LocalDateTime.now(ZoneOffset.UTC).minusDays(3))
                    //.lte(LocalDateTime.now(ZoneOffset.UTC));
                    .gte(questionQueryForm.getDate());
            boolQueryBuilder.filter(timeRangeQuery);
        }

        Pageable page = PageRequest.of(questionQueryForm.getCurrentPage() - 1, questionQueryForm.getPageSize());
        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(functionScoreQueryBuilder)
                .withSort(SortBuilders.scoreSort().order(SortOrder.DESC))
                .withPageable(page)
                .build();

        // 3.执行查询
        SearchHits<QuestionDoc> result = elasticsearchRestTemplate.search(query, QuestionDoc.class);
        List<QuestionVO> questionVOList = convertSearchHits(result, selfId, isSeo);
        //封装分页结果
        return convertToPageResult(result, questionVOList, questionQueryForm);
    }

    /**
     * 查询最新问题
     *
     * @param questionQueryForm
     * @param selfId
     * @return
     */
    public PageResult<QuestionVO> queryNewQuestion(QuestionQueryForm questionQueryForm, Long selfId, Boolean isSeo) {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        Pageable page = PageRequest.of(questionQueryForm.getCurrentPage() - 1, questionQueryForm.getPageSize());
        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(queryBuilder)
                .withSort(SortBuilders.fieldSort("createdTime").order(SortOrder.DESC))
                .withPageable(page)
                .build();
        // 3.执行查询
        SearchHits<QuestionDoc> result = elasticsearchRestTemplate.search(query, QuestionDoc.class);
        List<QuestionVO> questionVOList = convertSearchHits(result, selfId, isSeo);
        //封装分页结果
        return convertToPageResult(result, questionVOList, questionQueryForm);
    }

    public PageResult<QuestionVO> convertToPageResult(SearchHits<QuestionDoc> result, List<QuestionVO> questionVOList, QuestionQueryForm questionQueryForm) {
        return getQuestionVOPageResult(result, questionVOList, questionQueryForm.getCurrentPage(), questionQueryForm.getPageSize());
    }


    public List<QuestionVO> convertSearchHits(SearchHits<QuestionDoc> result, Long selfId, Boolean isSeo) {
        List<QuestionVO> questionVOList = new ArrayList<>();
        for (SearchHit<QuestionDoc> searchHit : result.getSearchHits()) {
            QuestionVO questionVO = new QuestionVO();
            BeanUtils.copyProperties(searchHit.getContent(), questionVO);
            QuestionUserLike userLike = null;
            //查询用户是否点赞,没有selfId则因为没有登录
            if (selfId != null && !isSeo) {
                userLike = questionUserLikeService.getQuestionUserLike(questionVO.getId(), selfId);
            }
            if (userLike != null) {
                questionVO.setLikeStatus(userLike.getStatus());
            } else {
                questionVO.setLikeStatus(0);
            }

            //查询用户是否收藏
            QuestionUserFavorite userFavorite = null;
            if (selfId != null && !isSeo) {
                userFavorite = questionUserFavoriteService.getQuestionUserFavorite(questionVO.getId(), selfId);
            }
            if (userFavorite != null) {
                questionVO.setFavoriteStatus(userFavorite.getStatus());
            } else {
                questionVO.setFavoriteStatus(0);
            }

            if (searchHit.getContent().getBestAnswerId() != null && !isSeo) {
                AnswerVO answer = questionClient.findAnswerVOById(searchHit.getContent().getBestAnswerId());
                questionVO.setBestAnswer(answer);
            }
            if (searchHit.getContent().getImages() != null) {
                questionVO.setImages(Arrays.asList(searchHit.getContent().getImages().split(",")));
            } else {
                questionVO.setImages(new ArrayList<>());
            }
            if (searchHit.getContent().getTags() != null) {
                questionVO.setTags(Arrays.asList(searchHit.getContent().getTags().split(",")));
            } else {
                questionVO.setTags(new ArrayList<>());
            }
            if (!isSeo) {
                User user = userClient.findById(questionVO.getUserId());
                if (user != null) {
                    questionVO.setNickname(user.getNickname());
                    questionVO.setAvatar(user.getAvatar());
                }
            }
            questionVOList.add(questionVO);
        }
        return questionVOList;
    }
}

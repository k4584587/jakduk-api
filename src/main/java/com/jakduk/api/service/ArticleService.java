package com.jakduk.api.service;

import com.jakduk.api.common.Constants;
import com.jakduk.api.common.board.category.BoardCategory;
import com.jakduk.api.common.board.category.BoardCategoryGenerator;
import com.jakduk.api.common.rabbitmq.RabbitMQPublisher;
import com.jakduk.api.common.util.AuthUtils;
import com.jakduk.api.common.util.DateUtils;
import com.jakduk.api.common.util.JakdukUtils;
import com.jakduk.api.common.util.UrlGenerationUtils;
import com.jakduk.api.exception.ServiceError;
import com.jakduk.api.exception.ServiceException;
import com.jakduk.api.model.aggregate.BoardFeelingCount;
import com.jakduk.api.model.aggregate.BoardTop;
import com.jakduk.api.model.aggregate.CommonCount;
import com.jakduk.api.model.db.Article;
import com.jakduk.api.model.db.ArticleComment;
import com.jakduk.api.model.db.Gallery;
import com.jakduk.api.model.db.UsersFeeling;
import com.jakduk.api.model.embedded.*;
import com.jakduk.api.model.simple.*;
import com.jakduk.api.repository.article.ArticleOnListRepository;
import com.jakduk.api.repository.article.ArticleRepository;
import com.jakduk.api.repository.article.comment.ArticleCommentRepository;
import com.jakduk.api.repository.gallery.GalleryRepository;
import com.jakduk.api.restcontroller.vo.board.*;
import com.jakduk.api.restcontroller.vo.home.LatestHomeArticle;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ArticleService {

	@Autowired private UrlGenerationUtils urlGenerationUtils;
	@Autowired private ArticleRepository articleRepository;
	@Autowired private ArticleOnListRepository articleOnListRepository;
	@Autowired private ArticleCommentRepository articleCommentRepository;
	@Autowired private GalleryRepository galleryRepository;
	@Autowired private CommonService commonService;
	@Autowired private CommonGalleryService commonGalleryService;
	@Autowired private RabbitMQPublisher rabbitMQPublisher;

	public Article findOneBySeq(String board, Integer seq) {
        return articleRepository.findOneByBoardAndSeq(board, seq)
                .orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));
    }

    /**
     * 자유게시판 글쓰기
	 *
     * @param subject 글 제목
     * @param content 글 내용
     * @param categoryCode 글 말머리 Code
     * @param galleries 글과 연동된 사진들
     * @param device 디바이스
     */
	public Article insertArticle(CommonWriter writer, Constants.BOARD_TYPE board, String subject, String content, String categoryCode,
								 List<Gallery> galleries, Constants.DEVICE_TYPE device) {

		if (! new BoardCategoryGenerator().existCategory(board, categoryCode))
			throw new ServiceException(ServiceError.NOT_FOUND_CATEGORY);

		// shortContent 만듦
		String stripHtmlContent = StringUtils.defaultIfBlank(JakdukUtils.stripHtmlTag(content), StringUtils.EMPTY);
		String shortContent = StringUtils.truncate(stripHtmlContent, Constants.BOARD_SHORT_CONTENT_LENGTH);

		// 글 상태
		ArticleStatus articleStatus = ArticleStatus.builder()
				.device(device)
				.build();

		ObjectId logId = new ObjectId();
		// lastUpdated
		LocalDateTime lastUpdated = LocalDateTime.ofInstant(logId.getDate().toInstant(), ZoneId.systemDefault());

		// 연관된 사진 id 배열 (검증 후)
		List<String> galleryIds = galleries.stream()
				.map(Gallery::getId)
				.collect(Collectors.toList());

		Article article = Article.builder()
				.writer(writer)
				.board(board.name())
				.category(categoryCode)
				.subject(subject)
				.content(content)
				.shortContent(shortContent)
				.views(0)
				.seq(commonService.getNextSequence(Constants.SEQ_BOARD))
				.status(articleStatus)
				.logs(this.initBoardLogs(logId, Constants.ARTICLE_HISTORY_TYPE.CREATE.name(), writer))
				.lastUpdated(lastUpdated)
				.linkedGallery(! galleries.isEmpty())
				.build();

		articleRepository.save(article);

	 	// 엘라스틱서치 색인 요청
		rabbitMQPublisher.indexDocumentBoard(article.getId(), article.getSeq(), article.getWriter(), article.getSubject(),
				article.getContent(), article.getBoard(), article.getCategory(), galleryIds);

		log.info("new post created. post seq={}, subject={}", article.getSeq(), article.getSubject());

		return article;
	}

	/**
	 * 자유게시판 글 고치기
	 *
	 * @param board 게시판
	 * @param seq 글 seq
	 * @param subject 글 제목
	 * @param content 글 내용
	 * @param categoryCode 글 말머리 Code
	 * @param galleryIds 글과 연동된 사진들
	 * @param device 디바이스
	 */
	public Article updateArticle(CommonWriter writer, String board, Integer seq, String subject, String content, String categoryCode,
								 List<String> galleryIds, Constants.DEVICE_TYPE device) {

		Article article = articleRepository.findOneByBoardAndSeq(board, seq)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));

		if (! article.getWriter().getUserId().equals(writer.getUserId()))
			throw new ServiceException(ServiceError.FORBIDDEN);

		// shortContent 만듦
		String stripHtmlContent = StringUtils.defaultIfBlank(JakdukUtils.stripHtmlTag(content), StringUtils.EMPTY);
		String shortContent = StringUtils.truncate(stripHtmlContent, Constants.BOARD_SHORT_CONTENT_LENGTH);

		article.setSubject(subject);
		article.setContent(content);
		article.setCategory(categoryCode);
		article.setShortContent(shortContent);
		article.setLinkedGallery(! galleryIds.isEmpty());

		// 글 상태
		ArticleStatus articleStatus = article.getStatus();

		if (Objects.isNull(articleStatus))
			articleStatus = new ArticleStatus();

        articleStatus.setDevice(device);
		article.setStatus(articleStatus);

		// boardHistory
		List<BoardLog> logs = article.getLogs();

		if (CollectionUtils.isEmpty(logs))
			logs = new ArrayList<>();

		ObjectId logId = new ObjectId();
		BoardLog log = new BoardLog(logId.toString(), Constants.ARTICLE_HISTORY_TYPE.EDIT.name(), new SimpleWriter(writer));
		logs.add(log);
		article.setLogs(logs);

		// lastUpdated
		article.setLastUpdated(LocalDateTime.ofInstant(logId.getDate().toInstant(), ZoneId.systemDefault()));

		articleRepository.save(article);

		ArticleService.log.info("post was edited. post seq={}, subject=", article.getSeq(), article.getSubject());

		// 엘라스틱서치 색인 요청
		rabbitMQPublisher.indexDocumentBoard(article.getId(), article.getSeq(), article.getWriter(), article.getSubject(),
				article.getContent(), article.getBoard(), article.getCategory(), galleryIds);

		return article;
	}

	/**
	 * 자유게시판 글 지움
	 *
	 * @param board 게시판
	 * @param seq 글 seq
	 * @return Constants.ARTICLE_DELETE_TYPE
     */
    public Constants.ARTICLE_DELETE_TYPE deleteArticle(CommonWriter writer, String board, Integer seq) {

        Article article = articleRepository.findOneByBoardAndSeq(board, seq)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));

        if (! article.getWriter().getUserId().equals(writer.getUserId()))
            throw new ServiceException(ServiceError.FORBIDDEN);

        ArticleItem articleItem = new ArticleItem(article.getId(), article.getSeq(), article.getBoard());

        Integer count = articleCommentRepository.countByArticle(articleItem);

        // 댓글이 하나라도 달리면 글을 몽땅 지우지 못한다.
        if (count > 0) {
			article.setContent(null);
			article.setSubject(null);
			article.setWriter(null);

            List<BoardLog> histories = article.getLogs();

            if (Objects.isNull(histories))
                histories = new ArrayList<>();

			ObjectId boardHistoryId = new ObjectId();
            BoardLog history = new BoardLog(boardHistoryId.toString(), Constants.ARTICLE_HISTORY_TYPE.DELETE.name(), new SimpleWriter(writer));
            histories.add(history);
			article.setLogs(histories);

            ArticleStatus articleStatus = article.getStatus();

            if (Objects.isNull(articleStatus))
                articleStatus = new ArticleStatus();

            articleStatus.setDelete(true);
			article.setStatus(articleStatus);
			article.setLinkedGallery(false);

			// lastUpdated
			article.setLastUpdated(LocalDateTime.ofInstant(boardHistoryId.getDate().toInstant(), ZoneId.systemDefault()));

			articleRepository.save(article);

			log.info("A post was deleted(post only). post seq={}, subject={}", article.getSeq(), article.getSubject());
        }
		// 몽땅 지우기
        else {
            articleRepository.delete(article);

			log.info("A post was deleted(all). post seq={}, subject={}", article.getSeq(), article.getSubject());
        }

        // 연결된 사진 끊기
        if (article.getLinkedGallery())
			commonGalleryService.unlinkGalleries(article.getId(), Constants.GALLERY_FROM_TYPE.ARTICLE);

		// 색인 지움
		rabbitMQPublisher.deleteDocumentBoard(article.getId());

        return count > 0 ? Constants.ARTICLE_DELETE_TYPE.CONTENT : Constants.ARTICLE_DELETE_TYPE.ALL;
    }

	/**
	 * 자유게시판 글 목록
     */
	public GetArticlesResponse getArticles(String board, String categoryCode, Integer page, Integer size) {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));
		Pageable pageable = new PageRequest(page - 1, size, sort);
		Page<ArticleOnList> postsPage;

		if ("ALL".equals(categoryCode)) {
			postsPage = articleOnListRepository.findByBoard(board, pageable);
		} else {
			postsPage = articleOnListRepository.findByBoardAndCategory(board, categoryCode, pageable);
		}

		// 자유 게시판 공지글 목록
		List<ArticleOnList> notices = articleRepository.findNotices(board, sort);

		// 게시물 VO 변환 및 썸네일 URL 추가
		Function<ArticleOnList, GetArticle> convertToFreePost = post -> {
			GetArticle freePosts = new GetArticle();
			BeanUtils.copyProperties(post, freePosts);

			if (post.getLinkedGallery()) {
				List<Gallery> galleries = galleryRepository.findByItemIdAndFromType(
						new ObjectId(post.getId()), Constants.GALLERY_FROM_TYPE.ARTICLE, 1);

				if (! CollectionUtils.isEmpty(galleries)) {
					List<BoardGallerySimple> boardGalleries = galleries.stream()
							.map(gallery -> BoardGallerySimple.builder()
									.id(gallery.getId())
									.thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.SMALL, gallery.getId()))
									.build())
							.collect(Collectors.toList());

					freePosts.setGalleries(boardGalleries);
				}
			}

			return freePosts;
		};

		List<GetArticle> getArticles = postsPage.getContent().stream()
				.map(convertToFreePost)
				.collect(Collectors.toList());

		List<GetArticle> freeNotices = notices.stream()
				.map(convertToFreePost)
				.collect(Collectors.toList());

		// Board ID 뽑아내기.
		ArrayList<ObjectId> ids = new ArrayList<>();

		getArticles.forEach(post -> ids.add(new ObjectId(post.getId())));
		freeNotices.forEach(post -> ids.add(new ObjectId(post.getId())));

		// 게시물의 댓글수
		Map<String, Integer> commentCounts = articleCommentRepository.findCommentsCountByIds(ids).stream()
				.collect(Collectors.toMap(CommonCount::getId, CommonCount::getCount));

		// 게시물의 감정수
		Map<String, BoardFeelingCount> feelingCounts = articleRepository.findUsersFeelingCount(ids).stream()
				.collect(Collectors.toMap(BoardFeelingCount::getId, Function.identity()));

		// 댓글수, 감정 표현수 합치기.
		Consumer<GetArticle> applyCounts = post -> {
			String boardId = post.getId();
			Integer commentCount = commentCounts.get(boardId);

			if (Objects.nonNull(commentCount))
				post.setCommentCount(commentCount);

			BoardFeelingCount feelingCount = feelingCounts.get(boardId);

			if (Objects.nonNull(feelingCount)) {
				post.setLikingCount(feelingCount.getUsersLikingCount());
				post.setDislikingCount(feelingCount.getUsersDislikingCount());
			}
		};

		getArticles.forEach(applyCounts);
		freeNotices.forEach(applyCounts);

		// 말머리
		List<BoardCategory> categories = new BoardCategoryGenerator().getCategories(Constants.BOARD_TYPE.valueOf(board), JakdukUtils.getLocale());
		Map<String, String> categoriesMap = null;

		if (! CollectionUtils.isEmpty(categories)) {
			categoriesMap = categories.stream()
					.collect(Collectors.toMap(BoardCategory::getCode, boardCategory -> boardCategory.getNames().get(0).getName()));

			categoriesMap.put("ALL", JakdukUtils.getResourceBundleMessage("messages.board", "board.category.all"));
		}

		return GetArticlesResponse.builder()
				.categories(categoriesMap)
				.articles(getArticles)
				.notices(freeNotices)
				.first(postsPage.isFirst())
				.last(postsPage.isLast())
				.totalPages(postsPage.getTotalPages())
				.totalElements(postsPage.getTotalElements())
				.numberOfElements(postsPage.getNumberOfElements())
				.size(postsPage.getSize())
				.number(postsPage.getNumber())
				.build();
	}

	/**
	 * 최근 글 가져오기
	 */
	public List<LatestHomeArticle> getLatestArticles() {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));

		List<ArticleOnList> posts = articleRepository.findLatest(sort, Constants.HOME_SIZE_POST);

		// 게시물 VO 변환 및 썸네일 URL 추가

		return posts.stream()
				.map(post -> {
					LatestHomeArticle latestHomeArticle = new LatestHomeArticle();
					BeanUtils.copyProperties(post, latestHomeArticle);

					if (post.getLinkedGallery()) {
						List<Gallery> galleries = galleryRepository.findByItemIdAndFromType(
								new ObjectId(post.getId()), Constants.GALLERY_FROM_TYPE.ARTICLE, 1);

						List<BoardGallerySimple> boardGalleries = galleries.stream()
								.sorted(Comparator.comparing(Gallery::getId))
								.limit(1)
								.map(gallery -> BoardGallerySimple.builder()
										.id(gallery.getId())
										.thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.SMALL, gallery.getId()))
										.build())
								.collect(Collectors.toList());

						latestHomeArticle.setGalleries(boardGalleries);
					}

					return latestHomeArticle;
				})
				.collect(Collectors.toList());
	}

    /**
     * 글 감정 표현.
     */
	public Article setFreeFeelings(CommonWriter writer, String board, Integer seq, Constants.FEELING_TYPE feeling) {

		Article article = articleRepository.findOneByBoardAndSeq(board, seq)
                .orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));

        String userId = writer.getUserId();
        String username = writer.getUsername();

		CommonWriter postWriter = article.getWriter();

		// 이 게시물의 작성자라서 감정 표현을 할 수 없음
		if (userId.equals(postWriter.getUserId()))
			throw new ServiceException(ServiceError.FEELING_YOU_ARE_WRITER);

		this.setUsersFeeling(userId, username, feeling, article);

		articleRepository.save(article);

		return article;
	}

	/**
	 * 게시판 댓글 달기
	 */
	public ArticleComment insertArticleComment(String board, Integer seq, CommonWriter writer, String content, List<Gallery> galleries,
											   Constants.DEVICE_TYPE device) {

		Article article = articleRepository.findOneByBoardAndSeq(board, seq)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));

		// 연관된 사진 id 배열 (검증 후)
		List<String> galleryIds = galleries.stream()
				.map(Gallery::getId)
				.collect(Collectors.toList());

		ArticleComment articleComment = ArticleComment.builder()
				.article(new ArticleItem(article.getId(), article.getSeq(), article.getBoard()))
				.writer(writer)
				.content(content)
				.status(new ArticleCommentStatus(device))
				.linkedGallery(! galleries.isEmpty())
				.logs(this.initBoardLogs(new ObjectId(), Constants.ARTICLE_COMMENT_HISTORY_TYPE.CREATE.name(), writer))
				.build();

		articleCommentRepository.save(articleComment);

		// 엘라스틱서치 색인 요청
		rabbitMQPublisher.indexDocumentComment(articleComment.getId(), articleComment.getArticle(), articleComment.getWriter(),
				articleComment.getContent(), galleryIds);

		return articleComment;
	}

	/**
	 * 게시판 댓글 고치기
	 */
	public ArticleComment updateArticleComment(String board, String id, CommonWriter writer, String content, List<String> galleryIds,
											   Constants.DEVICE_TYPE device) {

		ArticleComment articleComment = articleCommentRepository.findOneById(id)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_COMMENT));

		if (! articleComment.getWriter().getUserId().equals(writer.getUserId()))
			throw new ServiceException(ServiceError.FORBIDDEN);

		articleComment.setWriter(writer);
		articleComment.setContent(StringUtils.trim(content));
		ArticleCommentStatus articleCommentStatus = articleComment.getStatus();

		if (Objects.isNull(articleCommentStatus)) {
			articleCommentStatus = new ArticleCommentStatus(device);
		} else {
			articleCommentStatus.setDevice(device);
		}

		articleComment.setStatus(articleCommentStatus);
		articleComment.setLinkedGallery(! galleryIds.isEmpty());

		// boardLogs
		List<BoardLog> logs = Optional.ofNullable(articleComment.getLogs())
				.orElseGet(ArrayList::new);

		logs.add(new BoardLog(new ObjectId().toString(), Constants.ARTICLE_COMMENT_HISTORY_TYPE.EDIT.name(), new SimpleWriter(writer)));
		articleComment.setLogs(logs);

		articleCommentRepository.save(articleComment);

		// 엘라스틱서치 색인 요청
		rabbitMQPublisher.indexDocumentComment(articleComment.getId(), articleComment.getArticle(), articleComment.getWriter(),
				articleComment.getContent(), galleryIds);

		return articleComment;
	}

	/**
	 * 게시판 댓글 지움
	 */
	public void deleteArticleComment(String board, String id, CommonWriter writer) {

		ArticleComment articleComment = articleCommentRepository.findOneById(id)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_COMMENT));

		if (! articleComment.getWriter().getUserId().equals(writer.getUserId()))
			throw new ServiceException(ServiceError.FORBIDDEN);

		articleCommentRepository.delete(id);

		// 색인 지움
		rabbitMQPublisher.deleteDocumentComment(id);

		commonGalleryService.unlinkGalleries(id, Constants.GALLERY_FROM_TYPE.ARTICLE_COMMENT);
	}

	/**
	 * 게시물 댓글 목록
	 */
	public GetArticleDetailCommentsResponse getArticleDetailComments(String board, Integer seq, String commentId) {

		List<ArticleComment> comments;

		if (StringUtils.isNotBlank(commentId)) {
			comments  = articleCommentRepository.findByBoardSeqAndGTId(board, seq, new ObjectId(commentId));
		} else {
			comments  = articleCommentRepository.findByBoardSeqAndGTId(board, seq, null);
		}

		CommonWriter commonWriter = AuthUtils.getCommonWriter();

		ArticleSimple articleSimple = articleRepository.findBoardFreeOfMinimumBySeq(seq);
		ArticleItem articleItem = new ArticleItem(articleSimple.getId(), articleSimple.getSeq(), articleSimple.getBoard());

		Integer count = articleCommentRepository.countByArticle(articleItem);

		List<FreePostDetailComment> postComments = comments.stream()
				.map(boardFreeComment -> {
					FreePostDetailComment freePostDetailComment = new FreePostDetailComment();
					BeanUtils.copyProperties(boardFreeComment, freePostDetailComment);

					List<CommonFeelingUser> usersLiking = boardFreeComment.getUsersLiking();
					List<CommonFeelingUser> usersDisliking = boardFreeComment.getUsersDisliking();

					freePostDetailComment.setNumberOfLike(CollectionUtils.isEmpty(usersLiking) ? 0 : usersLiking.size());
					freePostDetailComment.setNumberOfDislike(CollectionUtils.isEmpty(usersDisliking) ? 0 : usersDisliking.size());

					if (Objects.nonNull(commonWriter))
						freePostDetailComment.setMyFeeling(JakdukUtils.getMyFeeling(commonWriter, usersLiking, usersDisliking));

					if (! ObjectUtils.isEmpty(boardFreeComment.getLogs())) {
						List<BoardFreeCommentLog> logs = boardFreeComment.getLogs().stream()
								.map(boardLog -> {
									BoardFreeCommentLog boardFreeCommentLog = new BoardFreeCommentLog();
									BeanUtils.copyProperties(boardLog, boardFreeCommentLog);
									LocalDateTime timestamp = DateUtils.dateToLocalDateTime(new ObjectId(boardFreeCommentLog.getId()).getDate());
									boardFreeCommentLog.setType(Constants.ARTICLE_COMMENT_HISTORY_TYPE.valueOf(boardLog.getType()));
									boardFreeCommentLog.setTimestamp(timestamp);

									return boardFreeCommentLog;
								})
								.sorted(Comparator.comparing(BoardFreeCommentLog::getId).reversed())
								.collect(Collectors.toList());

						freePostDetailComment.setLogs(logs);
					}

					// 엮인 사진들
					if (boardFreeComment.getLinkedGallery()) {
						List<Gallery> galleries = galleryRepository.findByItemIdAndFromType(
								new ObjectId(boardFreeComment.getId()), Constants.GALLERY_FROM_TYPE.ARTICLE_COMMENT, 100);

						if (! ObjectUtils.isEmpty(galleries)) {
							List<ArticleGallery> postDetailGalleries = galleries.stream()
									.map(gallery -> ArticleGallery.builder()
											.id(gallery.getId())
											.name(StringUtils.isNotBlank(gallery.getName()) ? gallery.getName() : gallery.getFileName())
											.imageUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.LARGE, gallery.getId()))
											.thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.LARGE, gallery.getId()))
											.build())
									.collect(Collectors.toList());

							freePostDetailComment.setGalleries(postDetailGalleries);
						}
					}

					return freePostDetailComment;
				})
				.collect(Collectors.toList());

		return GetArticleDetailCommentsResponse.builder()
				.comments(postComments)
				.count(count)
				.build();
	}

	/**
	 * 자유게시판 댓글 감정 표현.
	 *
	 * @param commentId 댓글 ID
	 * @param feeling 감정표현 종류
     * @return 자유게시판 댓글 객체
     */
	public ArticleComment setFreeCommentFeeling(CommonWriter writer, String commentId, Constants.FEELING_TYPE feeling) {

		ArticleComment boardComment = articleCommentRepository.findOneById(commentId)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_COMMENT));

		String userId = writer.getUserId();
		String username = writer.getUsername();

		CommonWriter postWriter = boardComment.getWriter();

		// 이 게시물의 작성자라서 감정 표현을 할 수 없음
		if (userId.equals(postWriter.getUserId()))
			throw new ServiceException(ServiceError.FEELING_YOU_ARE_WRITER);

		this.setUsersFeeling(userId, username, feeling, boardComment);

		articleCommentRepository.save(boardComment);

		return boardComment;
	}

	/**
	 * 자유게시판 글의 공지를 활성화/비활성화 한다.
	 * @param seq 글 seq
	 * @param isEnable 활성화/비활성화
     */
	public void setFreeNotice(CommonWriter writer, String board, Integer seq, Boolean isEnable) {

		Optional<Article> boardFree = articleRepository.findOneByBoardAndSeq(board, seq);

		if (!boardFree.isPresent())
			throw new ServiceException(ServiceError.NOT_FOUND_ARTICLE);

		Article getArticle = boardFree.get();
		ArticleStatus status = getArticle.getStatus();

		if (Objects.isNull(status))
			status = new ArticleStatus();

		Boolean isNotice = status.getNotice();

		if (Objects.nonNull(isNotice)) {
			if (isEnable && isNotice)
				throw new ServiceException(ServiceError.ALREADY_ENABLE);

			if (! isEnable && ! isNotice)
				throw new ServiceException(ServiceError.ALREADY_DISABLE);
		}

		if (isEnable) {
			status.setNotice(true);
		} else {
			status.setNotice(null);
		}

		getArticle.setStatus(status);

		List<BoardLog> histories = getArticle.getLogs();

		if (CollectionUtils.isEmpty(histories))
			histories = new ArrayList<>();

		String historyType = isEnable ? Constants.ARTICLE_HISTORY_TYPE.ENABLE_NOTICE.name() : Constants.ARTICLE_HISTORY_TYPE.DISABLE_NOTICE.name();
		BoardLog history = new BoardLog(new ObjectId().toString(), historyType, new SimpleWriter(writer));
		histories.add(history);

		getArticle.setLogs(histories);

		articleRepository.save(getArticle);

		if (log.isInfoEnabled())
			log.info("Set notice. post seq=" + getArticle.getSeq() + ", type=" + status.getNotice());
	}


	/**
	 * 자유게시판 주간 좋아요수 선두
     */
	public List<BoardTop> getFreeTopLikes(String board, ObjectId objectId) {
		return articleRepository.findTopLikes(board, objectId);
	}

	/**
	 * 자유게시판 주간 댓글수 선두
	 */
	public List<BoardTop> getFreeTopComments(String board, ObjectId objectId) {

		// 게시물의 댓글수
		Map<String, Integer> commentCounts = articleCommentRepository.findCommentsCountGreaterThanBoardIdAndBoard(objectId, board).stream()
				.collect(Collectors.toMap(CommonCount::getId, CommonCount::getCount));

		List<String> postIds = commentCounts.entrySet().stream()
				.map(Entry::getKey)
				.collect(Collectors.toList());

		// commentIds를 파라미터로 다시 글을 가져온다.
		List<Article> posts = articleRepository.findByIdInAndBoard(postIds, board);

		// sort
		Comparator<BoardTop> byCount = (b1, b2) -> b2.getCount() - b1.getCount();
		Comparator<BoardTop> byView = (b1, b2) -> b2.getViews() - b1.getViews();

		return posts.stream()
				.map(boardFree -> {
					BoardTop boardTop = new BoardTop();
					BeanUtils.copyProperties(boardFree, boardTop);
					boardTop.setCount(commentCounts.get(boardTop.getId()));
					return boardTop;
				})
				.sorted(byCount.thenComparing(byView))
				.limit(Constants.BOARD_TOP_LIMIT)
				.collect(Collectors.toList());
	}

	/**
	 * 자유게시판 댓글 목록
     */
	public GetArticleCommentsResponse getArticleComments(CommonWriter commonWriter, String board, Integer page, Integer size) {

		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));
		Pageable pageable = new PageRequest(page - 1, size, sort);

		Page<ArticleComment> commentsPage = articleCommentRepository.findByArticleBoard(board, pageable);

		// board id 뽑아내기.
		List<ObjectId> boardIds = commentsPage.getContent().stream()
				.map(comment -> new ObjectId(comment.getArticle().getId()))
				.distinct()
				.collect(Collectors.toList());

		// 댓글을 가진 글 목록
		List<ArticleOnSearch> posts = articleRepository.findPostsOnSearchByIds(boardIds);

		Map<String, ArticleOnSearch> postsHavingComments = posts.stream()
				.collect(Collectors.toMap(ArticleOnSearch::getId, Function.identity()));

		List<GetArticleComment> getArticleComments = commentsPage.getContent().stream()
				.map(boardFreeComment -> {
							GetArticleComment comment = new GetArticleComment();
							BeanUtils.copyProperties(boardFreeComment, comment);

							comment.setArticle(
									Optional.ofNullable(postsHavingComments.get(boardFreeComment.getArticle().getId()))
											.orElse(new ArticleOnSearch())
							);

							comment.setNumberOfLike(CollectionUtils.isEmpty(boardFreeComment.getUsersLiking()) ? 0 : boardFreeComment.getUsersLiking().size());
							comment.setNumberOfDislike(CollectionUtils.isEmpty(boardFreeComment.getUsersDisliking()) ? 0 : boardFreeComment.getUsersDisliking().size());

							if (Objects.nonNull(commonWriter))
								comment.setMyFeeling(JakdukUtils.getMyFeeling(commonWriter, boardFreeComment.getUsersLiking(),
										boardFreeComment.getUsersDisliking()));

							if (boardFreeComment.getLinkedGallery()) {
								List<Gallery> galleries = galleryRepository.findByItemIdAndFromType(
										new ObjectId(boardFreeComment.getId()), Constants.GALLERY_FROM_TYPE.ARTICLE_COMMENT, 100);

								if (! CollectionUtils.isEmpty(galleries)) {
									List<BoardGallerySimple> boardGalleries = galleries.stream()
											.map(gallery -> BoardGallerySimple.builder()
													.id(gallery.getId())
													.thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.SMALL, gallery.getId()))
													.build())
											.collect(Collectors.toList());

									comment.setGalleries(boardGalleries);
								}
							}

							return comment;
						}
				)
				.collect(Collectors.toList());

		return GetArticleCommentsResponse.builder()
				.comments(getArticleComments)
				.first(commentsPage.isFirst())
				.last(commentsPage.isLast())
				.totalPages(commentsPage.getTotalPages())
				.totalElements(commentsPage.getTotalElements())
				.numberOfElements(commentsPage.getNumberOfElements())
				.size(commentsPage.getSize())
				.number(commentsPage.getNumber())
				.build();
	}

	/**
	 * RSS 용 게시물 목록
	 */
	public List<ArticleOnRSS> getBoardFreeOnRss(ObjectId objectId, Integer limit) {
		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));

		return articleRepository.findPostsOnRss(objectId, sort, limit);

	}

	/**
	 * 사이트맵 용 게시물 목록
	 */
	public List<ArticleOnSitemap> getBoardFreeOnSitemap(ObjectId objectId, Integer limit) {
		Sort sort = new Sort(Sort.Direction.DESC, Collections.singletonList("_id"));

		return articleRepository.findSitemapArticles(objectId, sort, limit);

	}

	/**
	 * 글 상세 객체 가져오기
	 */
	public ResponseEntity<GetArticleDetailResponse> getArticleDetail(String board, Integer seq, Boolean isAddCookie) {

		Article article = articleRepository.findOneBySeq(seq)
				.orElseThrow(() -> new ServiceException(ServiceError.NOT_FOUND_ARTICLE));

		if (! StringUtils.equals(article.getBoard(), board)) {
			return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
					.header(HttpHeaders.LOCATION, urlGenerationUtils.generateArticleDetailApiUrl(article.getBoard(), seq))
					.build();
		}

		if (isAddCookie)
			this.increaseViews(article);

		CommonWriter commonWriter = AuthUtils.getCommonWriter();

        // 글 상세
		ArticleDetail articleDetail = new ArticleDetail();
		BeanUtils.copyProperties(article, articleDetail);

		if (! CollectionUtils.isEmpty(article.getLogs())) {
			List<ArticleLog> logs = article.getLogs().stream()
					.map(boardLog -> {
						ArticleLog articleLog = new ArticleLog();
						BeanUtils.copyProperties(boardLog, articleLog);
						LocalDateTime timestamp = DateUtils.dateToLocalDateTime(new ObjectId(articleLog.getId()).getDate());
						articleLog.setType(Constants.ARTICLE_HISTORY_TYPE.valueOf(boardLog.getType()));
						articleLog.setTimestamp(timestamp);

						return articleLog;
					})
					.sorted(Comparator.comparing(ArticleLog::getId).reversed())
					.collect(Collectors.toList());

			articleDetail.setLogs(logs);
		}

		BoardCategory boardCategory = new BoardCategoryGenerator().getCategory(Constants.BOARD_TYPE.valueOf(board), article.getCategory(), JakdukUtils.getLocale());

		articleDetail.setCategory(boardCategory);
		articleDetail.setNumberOfLike(CollectionUtils.isEmpty(article.getUsersLiking()) ? 0 : article.getUsersLiking().size());
		articleDetail.setNumberOfDislike(CollectionUtils.isEmpty(article.getUsersDisliking()) ? 0 : article.getUsersDisliking().size());

		// 엮인 사진들
		if (article.getLinkedGallery()) {
            List<Gallery> galleries = galleryRepository.findByItemIdAndFromType(
                    new ObjectId(article.getId()), Constants.GALLERY_FROM_TYPE.ARTICLE, 100);

            if (! CollectionUtils.isEmpty(galleries)) {
                List<ArticleGallery> postDetailGalleries = galleries.stream()
                        .map(gallery -> ArticleGallery.builder()
                                .id(gallery.getId())
                                .name(StringUtils.isNoneBlank(gallery.getName()) ? gallery.getName() : gallery.getFileName())
                                .imageUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.LARGE, gallery.getId()))
                                .thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.LARGE, gallery.getId()))
                                .build())
                        .collect(Collectors.toList());

                articleDetail.setGalleries(postDetailGalleries);
            }
        }

        // 나의 감정 상태
		if (Objects.nonNull(commonWriter))
			articleDetail.setMyFeeling(JakdukUtils.getMyFeeling(commonWriter, article.getUsersLiking(), article.getUsersDisliking()));

		// 앞, 뒤 글
		ArticleSimple prevPost = articleRepository.findByIdAndCategoryWithOperator(new ObjectId(articleDetail.getId()),
				Objects.nonNull(boardCategory) ? boardCategory.getCode() : null, Constants.CRITERIA_OPERATOR.GT);
		ArticleSimple nextPost = articleRepository.findByIdAndCategoryWithOperator(new ObjectId(articleDetail.getId()),
				Objects.nonNull(boardCategory) ? boardCategory.getCode() : null, Constants.CRITERIA_OPERATOR.LT);

        // 글쓴이의 최근 글
		List<LatestArticle> latestArticles = null;

		if (Objects.isNull(articleDetail.getStatus()) || BooleanUtils.isNotTrue(articleDetail.getStatus().getDelete())) {

			List<ArticleOnList> latestPostsByWriter = articleRepository.findByIdAndUserId(
					new ObjectId(articleDetail.getId()), articleDetail.getWriter().getUserId(), 3);

			// 게시물 VO 변환 및 썸네일 URL 추가
			latestArticles = latestPostsByWriter.stream()
					.map(post -> {
						LatestArticle latestArticle = new LatestArticle();
						BeanUtils.copyProperties(post, latestArticle);

						if (! post.getLinkedGallery()) {
							List<Gallery> latestPostGalleries = galleryRepository.findByItemIdAndFromType(new ObjectId(post.getId()),
									Constants.GALLERY_FROM_TYPE.ARTICLE, 1);

							if (! ObjectUtils.isEmpty(latestPostGalleries)) {
								List<BoardGallerySimple> boardGalleries = latestPostGalleries.stream()
										.map(gallery -> BoardGallerySimple.builder()
												.id(gallery.getId())
												.thumbnailUrl(urlGenerationUtils.generateGalleryUrl(Constants.IMAGE_SIZE_TYPE.SMALL, gallery.getId()))
												.build())
										.collect(Collectors.toList());

								latestArticle.setGalleries(boardGalleries);
							}
						}

						return latestArticle;
					})
					.collect(Collectors.toList());
		}

		return ResponseEntity.ok()
				.body(GetArticleDetailResponse.builder()
						.article(articleDetail)
						.prevArticle(prevPost)
						.nextArticle(nextPost)
						.latestArticlesByWriter(CollectionUtils.isEmpty(latestArticles) ? null : latestArticles)
						.build());
	}

	/**
	 * 읽음수 1 증가
	 */
	private void increaseViews(Article article) {
		int views = article.getViews();
		article.setViews(++views);
		articleRepository.save(article);
	}

	/**
	 * BoardLogs 생성
	 */
	private List<BoardLog> initBoardLogs(ObjectId objectId, String type, CommonWriter writer) {
		List<BoardLog> logs = new ArrayList<>();
		BoardLog history = new BoardLog(objectId.toString(), type, new SimpleWriter(writer));
		logs.add(history);

		return logs;
	}

	/**
	 * 감정 표현 CRUD
	 *
	 * @param userId 회원 ID
	 * @param username 회원 별명
	 * @param feeling 입력받은 감정
	 * @param usersFeeling 편집할 객체
	 */
	private void setUsersFeeling(String userId, String username, Constants.FEELING_TYPE feeling, UsersFeeling usersFeeling) {

		List<CommonFeelingUser> usersLiking = usersFeeling.getUsersLiking();
		List<CommonFeelingUser> usersDisliking = usersFeeling.getUsersDisliking();

		if (CollectionUtils.isEmpty(usersLiking)) usersLiking = new ArrayList<>();
		if (CollectionUtils.isEmpty(usersDisliking)) usersDisliking = new ArrayList<>();

		// 해당 회원이 좋아요를 이미 했는지 검사
		Optional<CommonFeelingUser> alreadyLike = usersLiking.stream()
				.filter(commonFeelingUser -> commonFeelingUser.getUserId().equals(userId))
				.findFirst();

		// 해당 회원이 싫어요를 이미 했는지 검사
		Optional<CommonFeelingUser> alreadyDislike = usersDisliking.stream()
				.filter(commonFeelingUser -> commonFeelingUser.getUserId().equals(userId))
				.findFirst();

		CommonFeelingUser feelingUser = new CommonFeelingUser(new ObjectId().toString(), userId, username);

		switch (feeling) {
			case LIKE:
				// 이미 좋아요를 했을 때, 좋아요를 취소
				if (alreadyLike.isPresent()) {
					usersLiking.remove(alreadyLike.get());
				}
				// 이미 싫어요를 했을 때, 싫어요를 없애고 좋아요로 바꿈
				else if (alreadyDislike.isPresent()) {
					usersDisliking.remove(alreadyDislike.get());
					usersLiking.add(feelingUser);

					usersFeeling.setUsersDisliking(usersDisliking);
				}
				// 아직 감정 표현을 하지 않아 좋아요로 등록
				else {
					usersLiking.add(feelingUser);
				}

				usersFeeling.setUsersLiking(usersLiking);

				break;

			case DISLIKE:
				// 이미 싫어요를 했을 때, 싫어요를 취소
				if (alreadyDislike.isPresent()) {
					usersDisliking.remove(alreadyDislike.get());
				}
				// 이미 좋아요를 했을 때, 좋아요를 없애고 싫어요로 바꿈
				else if (alreadyLike.isPresent()) {
					usersLiking.remove(alreadyLike.get());
					usersDisliking.add(feelingUser);

					usersFeeling.setUsersLiking(usersLiking);
				}
				// 아직 감정 표현을 하지 않아 싫어요로 등록
				else {
					usersDisliking.add(feelingUser);
				}

				usersFeeling.setUsersDisliking(usersDisliking);

				break;
		}
	}

}

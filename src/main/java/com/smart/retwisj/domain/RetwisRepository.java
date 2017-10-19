/*
 * Copyright 2011 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.smart.retwisj.domain;

import com.smart.retwisj.web.WebPost;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.BulkMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.query.SortQuery;
import org.springframework.data.redis.core.query.SortQueryBuilder;
import org.springframework.data.redis.hash.BeanUtilsHashMapper;
import org.springframework.data.redis.support.atomic.RedisAtomicLong;
import org.springframework.data.redis.support.collections.*;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Twitter-clone on top of Redis.
 * 
 * @author Costin Leau
 */
@Repository
public class RetwisRepository {

	private static final Pattern MENTION_REGEX = Pattern.compile("@[\\w]+");

	private final StringRedisTemplate template;

	private final ValueOperations<String, String> valueOps;

	// 标识全局id，原子自增唯一
	private final RedisAtomicLong postIdCounter;
	private final RedisAtomicLong userIdCounter;

	// global users
	private RedisList<String> users;
	// global timeline
	private final RedisList<String> timeline;

	// Post <-> HashMap 转换器
	private final BeanUtilsHashMapper<Post> postMapper = new BeanUtilsHashMapper<>(Post.class);

	@Autowired
	public RetwisRepository(StringRedisTemplate template) {
		this.template = template;
		valueOps = template.opsForValue();

		users = new DefaultRedisList<String>(KeyUtils.users(), template);
		timeline = new DefaultRedisList<String>(KeyUtils.timeline(), template);

		userIdCounter = new RedisAtomicLong(KeyUtils.globalUid(), template.getConnectionFactory());
		postIdCounter = new RedisAtomicLong(KeyUtils.globalPid(), template.getConnectionFactory());
	}

	/**
	 * 添加用户
	 * @param name
	 * @param password
	 * @return
	 */
	public String addUser(String name, String password) {
		// 全局uid 增一
		String uid = String.valueOf(userIdCounter.incrementAndGet());

		// save user as hash
		// uid -> user
		BoundHashOperations<String, String, String> userOps = template.boundHashOps(KeyUtils.uid(uid));
		userOps.put("name", name);
		userOps.put("pass", password);

		// 保存uid到user:用户名name:uid
		valueOps.set(KeyUtils.user(name), uid);

		// 添加用户到全局列表
		users.addFirst(name);

		// 生成用户授权码并返回
		return addAuth(name);
	}

	/**
	 * 根据pid获取帖子，返回只读单一元素列表，web展示
	 * @param pid
	 * @return
	 */
	public List<WebPost> getPost(String pid) {
		return Collections.singletonList(convertPost(pid, post(pid)));
	}

	/**
	 * 根据用户uid，返回指定范围的帖子，web展示
	 * @param uid
	 * @param range
	 * @return
	 */
	public List<WebPost> getPosts(String uid, Range range) {
		return convertPidsToPosts(KeyUtils.posts(uid), range);
	}

	/**
	 * 根据用户id，获取指定范围的时间线上的帖子
	 * @param uid
	 * @param range
	 * @return
	 */
	public List<WebPost> getTimeline(String uid, Range range) {
		return convertPidsToPosts(KeyUtils.timeline(uid), range);
	}

	/**
	 * 根据用户id，获取粉丝列表
	 * @param uid
	 * @return
	 */
	public Collection<String> getFollowers(String uid) {
		return covertUidsToNames(KeyUtils.followers(uid));
	}

	/**
	 * 根据用户id，获取关注的用户名列表
	 * @param uid
	 * @return
	 */
	public Collection<String> getFollowing(String uid) {
		return covertUidsToNames(KeyUtils.following(uid));
	}

	/**
	 * 获取用户特定范围内被提到的帖子
	 * @param uid
	 * @param range
	 * @return
	 */
	public List<WebPost> getMentions(String uid, Range range) {
		return convertPidsToPosts(KeyUtils.mentions(uid), range);
	}

	/**
	 * 获取指定范围的时间线上的帖子
	 * @param range
	 * @return
	 */
	public Collection<WebPost> timeline(Range range) {
		return convertPidsToPosts(KeyUtils.timeline(), range);
	}

	/**
	 * 获取指定范围的新增用户
	 * @param range
	 * @return
	 */
	public Collection<String> newUsers(Range range) {
		return users.range(range.begin, range.end);
	}

	/**
	 * 保存用户帖子
	 * @param username
	 * @param post
	 */
	public void post(String username, WebPost post) {
		Post p = post.asPost();

		String uid = findUid(username);
		p.setUid(uid);

		// 生成pid
		String pid = String.valueOf(postIdCounter.incrementAndGet());

		// 是否回复贴
		String replyName = post.getReplyTo();
		if (StringUtils.hasText(replyName)) {
			String mentionUid = findUid(replyName);
			p.setReplyUid(mentionUid);
			// handle mentions below
			p.setReplyPid(post.getReplyPid());
		}
		System.out.println("bad-boy ========= post: " + post.toString());
		// add post
		post(pid).putAll(postMapper.toHash(p));

		// add links
		posts(uid).addFirst(pid);
		timeline(uid).addFirst(pid);

		// update followers
		for (String follower : followers(uid)) {
			timeline(follower).addFirst(pid);
		}

		timeline.addFirst(pid);
		handleMentions(p, pid, replyName);
	}

	/**
	 * 将帖子中@的用户，与其关联
	 * @param post
	 * @param pid
	 * @param name
	 */
	private void handleMentions(Post post, String pid, String name) {
		// find mentions
		Collection<String> mentions = findMentions(post.getContent());

		for (String mention : mentions) {
			String uid = findUid(mention);
			if (uid != null) {
				mentions(uid).addFirst(pid);
			}
		}
	}

	/**
	 * 根据用户名获取uid
	 * @param name
	 * @return
	 */
	public String findUid(String name) {
		return valueOps.get(KeyUtils.user(name));
	}

	public boolean isUserValid(String name) {
		return template.hasKey(KeyUtils.user(name));
	}

	public boolean isPostValid(String pid) {
		return template.hasKey(KeyUtils.post(pid));
	}

	/**
	 * 根据用户id查找用户名
	 * @param uid
	 * @return
	 */
	private String findName(String uid) {
		if (!StringUtils.hasText(uid)) {
			return "";
		}
		BoundHashOperations<String, String, String> userOps = template.boundHashOps(KeyUtils.uid(uid));
		return userOps.get("name");
	}

	/**
	 * 判断用户名对应的密码是否相同
	 * @param user
	 * @param pass
	 * @return
	 */
	public boolean auth(String user, String pass) {
		// find uid
		String uid = findUid(user);
		if (StringUtils.hasText(uid)) {
			BoundHashOperations<String, String, String> userOps = template.boundHashOps(KeyUtils.uid(uid));
			return userOps.get("pass").equals(pass);
		}

		return false;
	}

	public String findNameForAuth(String value) {
		String uid = valueOps.get(KeyUtils.authKey(value));
		return findName(uid);
	}

	/**
	 * 获取用户授权码
	 * @param name
	 * @return
	 */
	public String addAuth(String name) {
		String uid = findUid(name);
		// add random auth key relation
		String auth = UUID.randomUUID().toString();
		// 保存用户授权码到uid:用户id:auth
		valueOps.set(KeyUtils.auth(uid), auth);
		// 保存用户id到auth:授权码auth
		valueOps.set(KeyUtils.authKey(auth), uid);
		return auth;
	}

	/**
	 * 删除auth
	 * @param user
	 */
	public void deleteAuth(String user) {
		String uid = findUid(user);

		String authKey = KeyUtils.auth(uid);
		String auth = valueOps.get(authKey);

		template.delete(Arrays.asList(authKey, KeyUtils.authKey(auth)));
	}

	public boolean hasMorePosts(String targetUid, Range range) {
		return posts(targetUid).size() > range.end + 1;
	}

	public boolean hasMoreTimeline(String targetUid, Range range) {
		return timeline(targetUid).size() > range.end + 1;
	}


	public boolean hasMoreTimeline(Range range) {
		return timeline.size() > range.end + 1;
	}

	public boolean isFollowing(String uid, String targetUid) {
		return following(uid).contains(targetUid);
	}

	// 关注
	public void follow(String targetUser) {
		String targetUid = findUid(targetUser);

		following(RetwisSecurity.getUid()).add(targetUid);
		followers(targetUid).add(RetwisSecurity.getUid());
	}

	// 取消关注
	public void stopFollowing(String targetUser) {
		String targetUid = findUid(targetUser);

		following(RetwisSecurity.getUid()).remove(targetUid);
		followers(targetUid).remove(RetwisSecurity.getUid());
	}

	// uid关注的用户 同时关注targetUid的用户
	public List<String> alsoFollowed(String uid, String targetUid) {
		RedisSet<String> tempSet = following(uid).intersectAndStore(
				followers(targetUid),
				KeyUtils.alsoFollowed(uid, targetUid));

		String key = tempSet.getKey();
		template.expire(key, 5, TimeUnit.SECONDS);

		return covertUidsToNames(key);
	}

	// uid关注的用户 同时也是targetUid关注的用户
	public List<String> commonFollowers(String uid, String targetUid) {
		RedisSet<String> tempSet = following(uid).intersectAndStore(
				following(targetUid),
				KeyUtils.commonFollowers(uid, targetUid));

		tempSet.expire(5, TimeUnit.SECONDS);

		return covertUidsToNames(tempSet.getKey());
	}

	// collections mapping the core data structures

	private RedisList<String> timeline(String uid) {
		return new DefaultRedisList<String>(KeyUtils.timeline(uid), template);
	}

	private RedisSet<String> following(String uid) {
		return new DefaultRedisSet<String>(KeyUtils.following(uid), template);
	}

	private RedisSet<String> followers(String uid) {
		return new DefaultRedisSet<String>(KeyUtils.followers(uid), template);
	}

	private RedisList<String> mentions(String uid) {
		return new DefaultRedisList<String>(KeyUtils.mentions(uid), template);
	}

	private RedisMap<String, String> post(String pid) {
		return new DefaultRedisMap<String, String>(KeyUtils.post(pid), template);
	}

	private RedisList<String> posts(String uid) {
		return new DefaultRedisList<String>(KeyUtils.posts(uid), template);
	}

	// various util methods

	// 将回复贴内容中 @用户名 添加超链接
	private String replaceReplies(String content) {
		Matcher regexMatcher = MENTION_REGEX.matcher(content);
		while (regexMatcher.find()) {
			String match = regexMatcher.group();
			int start = regexMatcher.start();
			int stop = regexMatcher.end();

			String uName = match.substring(1);
			if (isUserValid(uName)) {
				content = content.substring(0, start) + "<a href=\"!" + uName + "\">" + match + "</a>"
						+ content.substring(stop);
			}
		}
		return content;
	}

	/**
	 * 根据用户id列表，获取用户名列表
	 * @param key
	 * @return
	 */
	private List<String> covertUidsToNames(String key) {
		return template.sort(
				SortQueryBuilder.sort(key)
						.noSort()
						.get("uid:*->name")
						.build());
	}

	/**
	 * 根据指定范围的pids获取并转为web展示的WebPost列表
	 * @param key
	 * @param range
	 * @return
	 */
	private List<WebPost> convertPidsToPosts(String key, Range range) {
		String pid = "pid:*->";
		final String pidKey = "#";
		final String uid = "uid";
		final String content = "content";
		final String replyPid = "replyPid";
		final String replyUid = "replyUid";
		final String time = "time";

		SortQuery<String> query = SortQueryBuilder.sort(key)
				.noSort()
				.get(pidKey)
				.get(pid + uid)
				.get(pid + content)
				.get(pid + replyPid)
				.get(pid + replyUid)
				.get(pid + time)
				.limit(range.begin, range.end)
				.build();
		BulkMapper<WebPost, String> hm = new BulkMapper<WebPost, String>() {
			@Override
			public WebPost mapBulk(List<String> bulk) {
				Map<String, String> map = new LinkedHashMap<String, String>();
				Iterator<String> iterator = bulk.iterator();

				String pid = iterator.next();
				map.put(uid, iterator.next());
				map.put(content, iterator.next());
				map.put(replyPid, iterator.next());
				map.put(replyUid, iterator.next());
				map.put(time, iterator.next());

				return convertPost(pid, map);
			}
		};
		List<WebPost> sort = template.sort(query, hm);
		return sort;
	}

	/**
	 * 将redis中类型hash转换为web展示的帖子
	 * @param pid
	 * @param hash
	 * @return
	 */
	private WebPost convertPost(String pid, Map hash) {
		Post post = postMapper.fromHash(hash);
		WebPost wPost = new WebPost(post);
		wPost.setPid(pid);
		wPost.setName(findName(post.getUid()));
		wPost.setReplyTo(findName(post.getReplyUid()));
		wPost.setContent(replaceReplies(post.getContent()));
		return wPost;
	}

	/**
	 * 返回内容中@的用户
	 * @param content
	 * @return
	 */
	public static Collection<String> findMentions(String content) {
		Matcher regexMatcher = MENTION_REGEX.matcher(content);
		List<String> mentions = new ArrayList<String>(4);

		while (regexMatcher.find()) {
			mentions.add(regexMatcher.group().substring(1));
		}

		return mentions;
	}
}
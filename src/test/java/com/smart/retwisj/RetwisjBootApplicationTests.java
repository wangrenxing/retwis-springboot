package com.smart.retwisj;

import com.smart.retwisj.domain.Post;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.hash.Jackson2HashMapper;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest
public class RetwisjBootApplicationTests {

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RedisTemplate<String, Post> postRedisTemplate;

	@Test
	public void contextLoads() {
	}

	@Test
	public void test() {
		redisTemplate.opsForValue().set("name", "wrx");
		Assert.assertEquals("wrx", redisTemplate.opsForValue().get("name"));

		Post post = new Post();
		post.setContent("content");
		post.setReplyPid("r11");
		post.setReplyUid("u10");
		post.setTime(String.valueOf(System.currentTimeMillis()));


//		RedisMap<String, String> redisMap = new DefaultRedisMap<String, String>("pid:1", redisTemplate);
//		final HashMapper<Post, String, String> postMapper = new DecoratingStringHashMapper<Post>(
//				new JacksonHashMapper<Post>(Post.class));
//		redisMap.putAll(postMapper.toHash(post));
//		Assert.assertEquals("content", redisMap.get("content"));
//		Assert.assertEquals("pid:1", redisMap.getKey());

		Jackson2HashMapper pm = new Jackson2HashMapper(false);
		Map<String, Object> map = pm.toHash(post);
		System.out.println("map:  " + map.toString());
		System.out.println("post: " + pm.fromHash(map).toString());


		postRedisTemplate.opsForValue().set("post", post);
		Assert.assertEquals("content", postRedisTemplate.opsForValue().get("post").getContent());

//		BoundHashOperations<String, String, Object> boundHashOperations = postRedisTemplate.boundHashOps("uid:1");
//		Map<String, Object> map = new HashMap();
//		map.put("name", "wrx");
//		map.put("age", 30);
//		map.put("sex", "m");
//		boundHashOperations.putAll(map);


	}
}

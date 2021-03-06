/*
 * Copyright 2011-2013 the original author or authors.
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

package org.springframework.data.redis.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.ConnectionFactoryTracker;
import org.springframework.data.redis.SettingsUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * @author Costin Leau
 */
public class PubSubResubscribeTests {

	private static final String CHANNEL = "pubsub::test";
	private final Long ZERO = Long.valueOf(0);
	private final Long ONE = Long.valueOf(1);
	private final Long TWO = Long.valueOf(2);

	protected RedisMessageListenerContainer container;
	protected RedisConnectionFactory factory;
	protected RedisTemplate template;

	private final BlockingDeque<String> bag = new LinkedBlockingDeque<String>(99);

	private final Object handler = new Object() {
		public void handleMessage(String message) {
			System.out.println(message);
			bag.add(message);
		}
	};

	private final MessageListenerAdapter adapter = new MessageListenerAdapter(handler);

	@AfterClass
	public static void cleanUp() {
		ConnectionFactoryTracker.cleanUp();
	}

	@Before
	public void setUp() throws Exception {
		JedisConnectionFactory jedisConnFactory = new JedisConnectionFactory();
		jedisConnFactory.setUsePool(false);
		jedisConnFactory.setPort(SettingsUtils.getPort());
		jedisConnFactory.setHostName(SettingsUtils.getHost());
		jedisConnFactory.setDatabase(2);
		jedisConnFactory.afterPropertiesSet();

		factory = jedisConnFactory;

		template = new StringRedisTemplate(jedisConnFactory);
		ConnectionFactoryTracker.add(template.getConnectionFactory());

		bag.clear();

		adapter.setSerializer(template.getValueSerializer());
		adapter.afterPropertiesSet();

		container = new RedisMessageListenerContainer();
		container.setConnectionFactory(template.getConnectionFactory());
		container.setBeanName("container");
		container.addMessageListener(adapter, new ChannelTopic(CHANNEL));
		container.setTaskExecutor(new SyncTaskExecutor());
		container.setSubscriptionExecutor(new SimpleAsyncTaskExecutor());
		container.afterPropertiesSet();
		container.start();

		Thread.sleep(1000);
	}

	@After
	public void tearDown() throws Exception {
		container.destroy();
		((DisposableBean) factory).destroy();
		Thread.sleep(1000);
	}


	@Test
	public void testContainerPatternResubscribe() throws Exception {
		String payload1 = "do";
		String payload2 = "re mi";

		final String PATTERN = "p*";
		final String ANOTHER_CHANNEL = "pubsub::test::extra";

		MessageListenerAdapter anotherListener = new MessageListenerAdapter(handler);
		anotherListener.setSerializer(template.getValueSerializer());
		anotherListener.afterPropertiesSet();

		// remove adapter from all channels
		container.addMessageListener(anotherListener, new PatternTopic(PATTERN));
		container.removeMessageListener(adapter);

		// test no messages are sent just to patterns
		assertEquals(ONE, template.convertAndSend(CHANNEL, payload1));
		assertEquals(ONE, template.convertAndSend(ANOTHER_CHANNEL, payload2));

		List<String> msgs = new ArrayList<String>();
		msgs.add(bag.poll(1, TimeUnit.SECONDS));
		msgs.add(bag.poll(1, TimeUnit.SECONDS));

		assertEquals(2, msgs.size());
		// bind original listener on another channel
		container.addMessageListener(adapter, new ChannelTopic(ANOTHER_CHANNEL));

		assertEquals(ONE, template.convertAndSend(CHANNEL, payload1));
		assertEquals(TWO, template.convertAndSend(ANOTHER_CHANNEL, payload2));

		msgs.add(bag.poll(1, TimeUnit.SECONDS));
		msgs.add(bag.poll(1, TimeUnit.SECONDS));
		msgs.add(bag.poll(1, TimeUnit.SECONDS));
		// this message will not arrive on time
		assertNull(bag.poll(1, TimeUnit.SECONDS));

		// same message received first per channel subscription, second based on the pattern 
		assertEquals(5, msgs.size());

		assertTrue(msgs.contains(payload1));
		assertTrue(msgs.contains(payload2));
	}

	@Test
	public void testContainerChannelResubscribe() throws Exception {
		String payload1 = "do";
		String payload2 = "re mi";

		String anotherPayload1 = "od";
		String anotherPayload2 = "mi er";

		String ANOTHER_CHANNEL = "pubsub::test::extra";

		// bind listener on another channel
		container.addMessageListener(adapter, new ChannelTopic(ANOTHER_CHANNEL));
		container.removeMessageListener(null, new ChannelTopic(CHANNEL));

		assertEquals(ZERO, template.convertAndSend(CHANNEL, payload1));
		assertEquals(ZERO, template.convertAndSend(CHANNEL, payload2));

		assertEquals(ONE, template.convertAndSend(ANOTHER_CHANNEL, anotherPayload1));
		assertEquals(ONE, template.convertAndSend(ANOTHER_CHANNEL, anotherPayload2));

		Set<String> set = new LinkedHashSet<String>();
		set.add(bag.poll(1, TimeUnit.SECONDS));
		set.add(bag.poll(1, TimeUnit.SECONDS));

		System.out.println(set);

		assertFalse(set.contains(payload1));
		assertFalse(set.contains(payload2));

		assertTrue(set.contains(anotherPayload1));
		assertTrue(set.contains(anotherPayload2));
	}
}
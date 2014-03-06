package reactor.spring.messaging;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import org.apache.commons.io.output.NullOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author Jon Brisbin
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
public class RingBufferBatchingMessageHandlerTests {

	static int  RING_BUFFER_SIZE = 1024;
	static long TIMEOUT          = 1000;
	static int  MSG_COUNT        = 5000;

	@Autowired
	SubscribableChannel output;

	AtomicLong counter;

	@Before
	public void setup() {
		counter = new AtomicLong(0);
	}

	@Test
	public void ringBufferMessageBatcherReleasesWhenFull() throws InterruptedException {
		MessageHandler batcher = new RingBufferBatchingMessageHandler(
				new MessageHandler() {
					@Override
					public void handleMessage(Message<?> message) throws MessagingException {
						if(message.getPayload() instanceof List) {
							counter.incrementAndGet();
						}
					}
				},
				RING_BUFFER_SIZE
		);

		for(int i = 0; i < MSG_COUNT; i++) {
			batcher.handleMessage(new GenericMessage<String>("i=" + i));
		}

		assertThat("Latch was counted down", counter.get(), is(5l));
	}

	@Test
	public void testBatchMessageHandlerThroughput() {
		MessageHandler batcher = new RingBufferBatchingMessageHandler(
				new MessageHandler() {
					@Override
					public void handleMessage(Message<?> message) throws MessagingException {
						counter.addAndGet(((List)message.getPayload()).size());
					}
				},
				RING_BUFFER_SIZE
		);
		output.subscribe(batcher);

		Message<?> msg = new GenericMessage<Object>("Hello World!");
		long start = System.currentTimeMillis();
		while(System.currentTimeMillis() - start < TIMEOUT) {
			output.send(msg);
		}
		long end = System.currentTimeMillis();
		double elapsed = end - start;
		int throughput = (int)(counter.get() / (elapsed / 1000));

		System.out.format("batch handler throughput: %s/sec%n", throughput);
	}

	@Test
	public void testSingleMessageHandlerThroughput() {
		MessageHandler handler = new MessageHandler() {
			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				counter.incrementAndGet();
			}
		};
		output.subscribe(handler);

		Message<?> msg = new GenericMessage<Object>("Hello World!");
		long start = System.currentTimeMillis();
		while(System.currentTimeMillis() - start < TIMEOUT) {
			output.send(msg);
		}
		long end = System.currentTimeMillis();
		double elapsed = end - start;
		int throughput = (int)(counter.get() / (elapsed / 1000));

		System.out.format("single handler throughput: %s/sec%n", throughput);
	}

	@Test
	public void testBatchMessageHandlerSerializerThroughput() {
		final Kryo kryo = new Kryo();
		final Output kryoOut = new Output(new NullOutputStream());

		MessageHandler batcher = new RingBufferBatchingMessageHandler(
				new MessageHandler() {
					@Override
					public void handleMessage(Message<?> message) throws MessagingException {
						kryo.writeObject(kryoOut, message);
						counter.addAndGet(((List)message.getPayload()).size());
					}
				},
				RING_BUFFER_SIZE
		);
		output.subscribe(batcher);

		Message<?> msg = new GenericMessage<Object>("Hello World!");
		long start = System.currentTimeMillis();
		while(System.currentTimeMillis() - start < TIMEOUT) {
			output.send(msg);
		}
		long end = System.currentTimeMillis();
		double elapsed = end - start;
		int throughput = (int)(counter.get() / (elapsed / 1000));

		System.out.format("serializing kryo handler throughput: %s/sec%n", throughput);
	}

	@Configuration
	static class TestConfig {
		@Bean
		public SubscribableChannel output() {
			return new ExecutorSubscribableChannel();
		}
	}

}

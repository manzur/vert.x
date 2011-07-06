package org.nodex.examples.composition;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.MessageProperties;
import org.nodex.core.Callback;
import org.nodex.core.NoArgCallback;
import org.nodex.core.amqp.AmqpClient;
import org.nodex.core.amqp.AmqpConnection;
import org.nodex.core.amqp.AmqpMsgCallback;
import org.nodex.core.amqp.Channel;
import org.nodex.core.amqp.ChannelPool;
import org.nodex.core.buffer.Buffer;
import org.nodex.core.composition.Completion;
import org.nodex.core.composition.Composer;
import org.nodex.core.composition.Deferred;
import org.nodex.core.file.FileSystem;
import org.nodex.core.http.HttpCallback;
import org.nodex.core.http.HttpConnection;
import org.nodex.core.http.HttpRequest;
import org.nodex.core.http.HttpResponse;
import org.nodex.core.http.HttpServer;
import org.nodex.core.net.NetServer;
import org.nodex.core.redis.RedisConnection;
import org.nodex.core.redis.RedisClient;
import org.nodex.core.stomp.StompMsgCallback;
import org.nodex.core.stomp.StompClient;
import org.nodex.core.stomp.StompConnection;
import org.nodex.core.stomp.StompServer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User: tim
 * Date: 05/07/11
 * Time: 15:58
 */
public class CompositionExample {
  public static void main(String[] args) throws Exception {
    new CompositionExample().run();

    System.out.println("Any key to exit");
    System.in.read();
  }

  private CompositionExample() {
  }

  private static final String AMQP_QUEUE = "availability";
  private static final String STOMP_DESTINATION = "availability-request";

  private void run() {
    startStompServer();
    startHttpServer();
    startAMQPWorker();
    startSTOMPWorker();
  }

  private void startStompServer() {
    StompServer.createServer().listen(8181);
  }

  private void startHttpServer() {
    final ChannelPool chPool = ChannelPool.createPool();
    HttpServer.createServer(new Callback<HttpConnection>() {
      public void onEvent(final HttpConnection conn) {
        conn.request(new HttpCallback() {
          public void onRequest(HttpRequest req, final HttpResponse resp) {
            System.out.println("Request uri is " + req.uri);
            if (req.uri.equals("/")) {
              System.out.println("Serving index page");
              //Serve the main page
              FileSystem.instance.readFile("index.html", new Callback<Buffer>() {
                public void onEvent(Buffer data) {
                  resp.write(data);
                  resp.end();
                }
              });
            } else if (req.uri.startsWith("/submit")) {
              //We have received a request for price/stock information, so we send a message to Rabbit with the name of the item
              final String item = req.getParam("item");
              chPool.getChannel(new Callback<Channel>() {
                public void onEvent(final Channel ch) {

                  //FIXME - wrap this ugly BasicProperties class with something nicer to use
                  //Also Don't want to be leaking Java client stuff
                  AMQP.BasicProperties props = MessageProperties.MINIMAL_BASIC;
                  props.setHeaders(new HashMap<String, Object>());
                  props.getHeaders().put("item", item);

                  ch.request("", AMQP_QUEUE, props, null, new AmqpMsgCallback() {
                    public void onMessage(AMQP.BasicProperties respProps, byte[] bod) {
                      //We get a response back with the price and number of items in stock
                      int price = (Integer)respProps.getHeaders().get("price");
                      int stock = (Integer)respProps.getHeaders().get("stock");
                      String content = "<html><body>Price is: " + price + "<br>Stock is: " + stock + "</body></html>";
                      resp.write(content, "UTF-8").end();
                    }
                  });
                }
              });
            }
          }
        });
      }
    }).listen(8080);
  }

  /*
  The AMQP worker consumes from the queue and then calls redis to get the price for the item, and does a request/response
   from the STOMP queue to get the stock availability of the item. This is done in parallel.
   When both results are in, it sends back a message with both results
   */
  private void startAMQPWorker() {

    final AtomicReference<RedisConnection> redisConn = new AtomicReference<RedisConnection>();
    final Completion redisConnected = new Completion();

    //Create redis connection
    RedisClient.createClient().connect(6379, "localhost", new Callback<RedisConnection>() {
      public void onEvent(final RedisConnection conn) {
        //We need to add a little reference data for this prices
        conn.set("bicycle", "125", new NoArgCallback() {
          public void onEvent() {
            conn.set("aardvark", "333", new NoArgCallback() {
              public void onEvent() {
                redisConn.set(conn);
                redisConnected.complete();
              }
            });
          }
        });
      }
    });

    final AtomicReference<StompConnection> stompConn = new AtomicReference<StompConnection>();
    final Completion stompConnected = new Completion();

    //Create STOMP connection
    StompClient.connect(8181, new Callback<StompConnection>() {
      public void onEvent(StompConnection conn) {
        stompConn.set(conn);
        stompConnected.complete();
      }
    });

    // Create and start the worker
    AmqpClient.createClient().connect(new Callback<AmqpConnection>() {
      public void onEvent(AmqpConnection conn) {
        conn.createChannel(new Callback<Channel>() {
          public void onEvent(final Channel ch) {
            //Declare the queue
            ch.declareQueue(AMQP_QUEUE, false, true, true, new NoArgCallback() {
              public void onEvent() {
                ch.subscribe(AMQP_QUEUE, true, new AmqpMsgCallback() {
                public void onMessage(final AMQP.BasicProperties props, byte[] body) {
                  final String item = props.getHeaders().get("item").toString();
                  Composer comp = Composer.compose();
                  final AtomicInteger price = new AtomicInteger(0);
                  Completion redisGet = redisConn.get().get(item, new Callback<String>() {
                    public void onEvent(String value) {
                      System.out.println("redis return value is " + value);
                      price.set(Integer.parseInt(value));
                    }
                  });

                  Map<String, String> headers = new HashMap<String, String>();
                  headers.put("item", item);

                  final AtomicInteger stock = new AtomicInteger(0);
                  Completion responseReturned = stompConn.get().request(STOMP_DESTINATION, headers, null, new StompMsgCallback() {
                    public void onMessage(Map<String, String> headers, Buffer body) {

                      int st = Integer.valueOf(headers.get("stock"));
                      System.out.println("Amount of stock is " + st);

                      stock.set(st);
                    }
                  });

                  comp.parallel(redisConnected, stompConnected) // First make sure we are connected to redis and stomp
                      .parallel(redisGet, responseReturned)     // Then execute redis get and stomp request/response in parallel
                      .then(new Deferred(new NoArgCallback() {  // Then send back a response with the price and stock
                        public void onEvent() {
                          //Now we send back a message with the price and stock
                          props.getHeaders().put("price", price.get());
                          props.getHeaders().put("stock", stock.get());
                          ch.publish("", props.getReplyTo(), props, (byte[]) null);
                        }
                      }))
                      .run();
                }
              });
              }
            });
          }
        });
      }
    });
  }

  /*
  The STOMP worker consumes from the price queue and sends back the number of items in stock for the item
   */
  private void startSTOMPWorker() {
    StompClient.connect(8181, new Callback<StompConnection>() {
      public void onEvent(final StompConnection conn) {
        conn.subscribe(STOMP_DESTINATION, new StompMsgCallback() {
          public void onMessage(Map<String, String> headers, Buffer body) {
            System.out.println("Sending back number of items in stock for item " + headers.get("item"));
            headers.put("stock", String.valueOf((int)(10 * Math.random())));
            conn.send(headers.get("reply-to"), headers, null);
          }
        });
      }
    });
  }


}

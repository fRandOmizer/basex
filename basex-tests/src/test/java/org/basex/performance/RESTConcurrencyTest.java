package org.basex.performance;

import static org.basex.query.func.http.HttpMethod.*;
import static org.junit.Assert.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import org.basex.*;
import org.basex.api.client.*;
import org.basex.core.cmd.*;
import org.basex.core.users.*;
import org.basex.io.*;
import org.basex.query.func.http.*;
import org.basex.util.*;
import org.basex.util.list.*;
import org.junit.*;
import org.junit.Test;

/**
 * Concurrency tests of BaseX REST API.
 *
 * @author BaseX Team 2005-15, BSD License
 * @author Dimitar Popov
 */
public final class RESTConcurrencyTest extends SandboxTest {
  /** HTTP server. */
  private static BaseXHTTP http;

  /** Time-out in (ms): increase if running on a slower system. */
  private static final long TIMEOUT = 600;
  /** Socket time-out in (ms). */
  private static final int SOCKET_TIMEOUT = 3000;
  /** BaseX HTTP base URL. */
  static final String BASE_URL = "http://localhost:8984/rest/" + NAME;

  /**
   * Create a test database and start BaseXHTTP.
   * @throws Exception if database cannot be created or server cannot be started
   */
  @BeforeClass
  public static void setUp() throws Exception {
    http = new BaseXHTTP("-U" + UserText.ADMIN, "-P" + UserText.ADMIN);
    try(final ClientSession cs = new ClientSession(context, UserText.ADMIN, UserText.ADMIN)) {
      cs.execute(new CreateDB(NAME));
    }
  }

  /**
   * Drop the test database and stop BaseXHTTP.
   * @throws Exception if database cannot be dropped or server cannot be stopped
   */
  @AfterClass
  public static void tearDown() throws Exception {
    try(final ClientSession cs = new ClientSession(context, UserText.ADMIN, UserText.ADMIN)) {
      cs.execute(new DropDB(NAME));
    }
    http.stop();
  }

  /**
   * Test 2 concurrent readers (GH-458).
   * <p><b>Test case:</b>
   * <ol>
   * <li/>start a long running reader;
   * <li/>start a fast reader: it should succeed.
   * </ol>
   * @throws Exception error during request execution
   */
  @Test
  public void testMultipleReaders() throws Exception {
    final String number = "63177";
    final String slowQuery = "?query=(1%20to%20100000000000)%5b.=0%5d";
    final String fastQuery = "?query=" + number;

    final Get slowAction = new Get(slowQuery);
    final Get fastAction = new Get(fastQuery);

    final ExecutorService exec = Executors.newFixedThreadPool(2);
    final Future<HTTPResponse> slow = exec.submit(slowAction);
    Performance.sleep(TIMEOUT); // delay in order to be sure that the reader has started
    final Future<HTTPResponse> fast = exec.submit(fastAction);

    try {
      final HTTPResponse result = fast.get();
      assertEquals(HTTPCode.OK, result.status);
      assertEquals(number, result.data);
    } finally {
      slowAction.stop = true;
      slow.get();
    }
  }

  /**
   * Test concurrent reader and writer (GH-458).
   * <p><b>Test case:</b>
   * <ol>
   * <li/>start a long running reader;
   * <li/>try to start a writer: it should time out;
   * <li/>stop the reader;
   * <li/>start the writer again: it should succeed.
   * </ol>
   * @throws Exception error during request execution
   */
  @Test
  @Ignore("There is no way to stop a query on the server!")
  public void testReaderWriter() throws Exception {
    final String readerQuery = "?query=(1%20to%20100000000000000)%5b.=0%5d";
    final String writerQuery = "/test.xml";
    final byte[] content = Token.token("<a/>");

    final Get readerAction = new Get(readerQuery);
    final Put writerAction = new Put(writerQuery, content);

    final ExecutorService exec = Executors.newFixedThreadPool(2);

    // start reader
    exec.submit(readerAction);
    Performance.sleep(TIMEOUT); // delay in order to be sure that the reader has started
    // start writer
    Future<HTTPResponse> writer = exec.submit(writerAction);

    try {
      final HTTPResponse result = writer.get(TIMEOUT, TimeUnit.MILLISECONDS);

      if(result.status.isSuccess()) fail("Database modified while a reader is running");
      throw new Exception(result.toString());
    } catch(final TimeoutException e) {
      // writer is blocked by the reader: stop it
      writerAction.stop = true;
    }

    // stop reader
    readerAction.stop = true;

    // start the writer again
    writer = exec.submit(writerAction);
    assertEquals(HTTPCode.CREATED, writer.get().status);
  }

  /**
   * Test concurrent writers (GH-458).
   * <p><b>Test case:</b>
   * <ol>
   * <li/>start several writers one after another;
   * <li/>all writers should succeed.
   * </ol>
   * @throws Exception error during request execution
   */
  @Test
  public void testMultipleWriters() throws Exception {
    final int count = 10;
    final String template =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<command xmlns=\"http://basex.org/rest\"><text><![CDATA[" +
        "ADD TO %1$d <node id=\"%1$d\"/>" +
        "]]></text></command>";

    final ArrayList<Future<HTTPResponse>> tasks = new ArrayList<>();
    final ExecutorService exec = Executors.newFixedThreadPool(count);

    // start all writers (not at the same time, but still in parallel)
    for(int i = 0; i < count; i++) {
      final String command = String.format(template, i);
      tasks.add(exec.submit(new Post("", Token.token(command))));
    }

    // check if all have finished successfully
    for(final Future<HTTPResponse> task : tasks) {
      assertEquals(HTTPCode.OK, task.get(TIMEOUT, TimeUnit.MILLISECONDS).status);
    }
  }

  // REST API:

  /** REST GET request. */
  private static class Get implements Callable<HTTPResponse> {
    /** Request URI. */
    protected final URI uri;
    /** Stop signal. */
    public volatile boolean stop;

    /**
     * Construct a new GET request.
     * @param request request string without the base URI
     */
    public Get(final String request) {
      uri = URI.create(BASE_URL + request);
    }

    @Override
    public HTTPResponse call() throws Exception {
      final HttpURLConnection hc = (HttpURLConnection) uri.toURL().openConnection();
      hc.setReadTimeout(SOCKET_TIMEOUT);
      try {
        while(!stop) {
          try {
            final int code = hc.getResponseCode();

            final InputStream input = hc.getInputStream();
            final ByteList bl = new ByteList();
            for(int i; (i = input.read()) != -1;) bl.add(i);

            return new HTTPResponse(code, bl.toString());
          } catch(final SocketTimeoutException e) { }
        }
        return null;
      } finally {
        hc.disconnect();
      }
    }
  }

  /** REST PUT request. */
  private static class Put implements Callable<HTTPResponse> {
    /** Request URI. */
    private final URI uri;
    /** Content to send to the server. */
    private final byte[] data;
    /** HTTP method. */
    protected HttpMethod method;
    /** Stop signal. */
    public volatile boolean stop;

    /**
     * Construct a new PUT request.
     * @param request request string without the base URI
     * @param data data to send to the server
     */
    public Put(final String request, final byte[] data) {
      this(request, data, PUT);
    }

    /**
     * Construct a new request.
     * @param request request string without the base URI
     * @param data data to send to the server
     * @param method HTTP method
     */
    protected Put(final String request, final byte[] data, final HttpMethod method) {
      this.data = data;
      this.method = method;
      uri = URI.create(BASE_URL + request);
    }

    @Override
    public HTTPResponse call() throws Exception {
      final HttpURLConnection hc = (HttpURLConnection) uri.toURL().openConnection();
      try {
        hc.setDoOutput(true);
        hc.setRequestMethod(method.name());
        hc.setRequestProperty(HttpText.CONTENT_TYPE, MimeTypes.APP_XML);
        hc.getOutputStream().write(data);
        hc.getOutputStream().close();

        hc.setReadTimeout(SOCKET_TIMEOUT);
        while(!stop) {
          try {
            return new HTTPResponse(hc.getResponseCode());
          } catch(final SocketTimeoutException e) { }
        }
        return null;
      } finally {
        hc.disconnect();
      }
    }
  }

  /** REST POST request. */
  private static class Post extends Put {
    /**
     * Construct a new POST request.
     * @param request request string without the base URI
     * @param data data to send to the server
     */
    public Post(final String request, final byte[] data) {
      super(request, data, POST);
    }
  }

  // Toolbox

  /** Simple HTTP response. */
  private static class HTTPResponse {
    /** Status code. */
    public final HTTPCode status;
    /** Response data or {@code null} if no data was returned. */
    public final String data;

    /**
     * Constructor.
     * @param code HTTP response status code
     */
    public HTTPResponse(final int code) {
      this(code, null);
    }

    /**
     * Constructor.
     * @param code HTTP response status code
     * @param data data
     */
    public HTTPResponse(final int code, final String data) {
      this.data = data;
      status = HTTPCode.valueOf(code);
    }
  }

  /** HTTP response codes. */
  private static enum HTTPCode {
    /** 100: Continue. */
    CONTINUE(100, "Continue"),
    /** 200: OK. */
    OK(200, "OK"),
    /** 201: Created. */
    CREATED(201, "Created"),
    /** 400: Bad Request. */
    BAD_REQUEST(400, "Bad Request"),
    /** 401: Unauthorized. */
    UNAUTHORIZED(401, "Unauthorized"),
    /** 403: Forbidden. */
    FORBIDDEN(403, "Forbidden"),
    /** 404: Not Found. */
    NOT_FOUND(403, "Not Found");

    /** HTTP response code. */
    public final int code;
    /** HTTP response message. */
    public final String message;

    /**
     * Constructor.
     * @param code code
     * @param message message
     */
    private HTTPCode(final int code, final String message) {
      this.code = code;
      this.message = message;
    }

    /**
     * Is the current code a "Success" code?
     * @return {@code true} if the current code is a "Success" code
     */
    public boolean isSuccess() {
      return 200 <= code && code < 300;
    }

    @Override
    public String toString() {
      return code + ": " + message;
    }

    /**
     * Get the enum value given the numeric code.
     * @param code HTTP response code
     * @return enum value
     */
    public static HTTPCode valueOf(final int code) {
      for(final HTTPCode h : HTTPCode.values()) {
        if(h.code == code) return h;
      }
      return null;
    }
  }
}

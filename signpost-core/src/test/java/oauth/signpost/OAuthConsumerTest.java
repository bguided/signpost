
package oauth.signpost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.http.HttpParameters;
import oauth.signpost.http.HttpRequest;
import oauth.signpost.signature.HmacSha1MessageSigner;
import oauth.signpost.signature.OAuthMessageSigner;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnit44Runner;

@RunWith(MockitoJUnit44Runner.class)
public abstract class OAuthConsumerTest extends SignpostTestBase {

    protected OAuthConsumer consumer;

    protected abstract OAuthConsumer buildConsumer(String consumerKey, String consumerSecret,
            OAuthMessageSigner messageSigner);

    @Test(expected = OAuthExpectationFailedException.class)
    public void shouldThrowIfConsumerKeyNotSet() throws Exception {
        OAuthConsumer consumer = buildConsumer(null, CONSUMER_SECRET, null);
        consumer.setTokenWithSecret(TOKEN, TOKEN_SECRET);
        consumer.sign(httpGetMock);
    }

    @Test(expected = OAuthExpectationFailedException.class)
    public void shouldThrowIfConsumerSecretNotSet() throws Exception {
        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, null, null);
        consumer.setTokenWithSecret(TOKEN, TOKEN_SECRET);
        consumer.sign(httpGetMock);
    }

    @Test
    public void shouldSignHttpRequestMessage() throws Exception {

        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);

        consumer.setTokenWithSecret(TOKEN, TOKEN_SECRET);

        consumer.sign(httpGetMock);

        verify(httpGetMock).setHeader(eq("Authorization"),
                argThat(new IsCompleteListOfOAuthParameters()));
    }

    @Test
    public void shouldSignUrl() throws Exception {

        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);

        consumer.setTokenWithSecret(TOKEN, TOKEN_SECRET);

        String result = consumer.sign("http://www.example.com?q=1");
        assertNotNull(result);

        String[] parts = result.split("\\?");
        assertEquals("parameters are missing", 2, parts.length);
        assertEquals("http://www.example.com", parts[0]);

        HttpParameters params = OAuth.decodeForm(parts[1]);
        assertAllOAuthParametersExist(params);

        assertEquals("1", params.getFirst("q"));
    }

    @Test
    public void shouldIncludeOAuthAndQueryAndBodyParams() throws Exception {

        // mock a request that has custom query, body, and header params set
        HttpRequest request = mock(HttpRequest.class);
        when(request.getRequestUrl()).thenReturn("http://example.com?a=1");
        ByteArrayInputStream body = new ByteArrayInputStream("b=2".getBytes());
        when(request.getMessagePayload()).thenReturn(body);
        when(request.getContentType()).thenReturn(
                "application/x-www-form-urlencoded; charset=ISO-8859-1");
        when(request.getHeader("Authorization")).thenReturn(
                "OAuth realm=www.example.com, oauth_signature=12345, oauth_version=1.1");

        when(request.getMethod()).thenReturn("GET");
        OAuthMessageSigner signer = mock(HmacSha1MessageSigner.class);
        consumer.setMessageSigner(signer);

        consumer.sign(request);

        // verify that all custom params are properly read and passed to the
        // message signer
        ArgumentMatcher<HttpParameters> hasAllParameters = new ArgumentMatcher<HttpParameters>() {
            public boolean matches(Object argument) {
                HttpParameters params = (HttpParameters)argument;
                assertEquals("1", params.get("a").first());
                assertEquals("2", params.get("b").first());
                assertEquals("1.1", params.get("oauth_version").first());
                return true;
            }
        };

        verify(signer).sign(same(request), argThat(hasAllParameters));
    }

    @Test
    public void shouldHonorManuallySetSigningParameters() throws Exception {

        // mock a request that has custom query, body, and header params set
        HttpRequest request = mock(HttpRequest.class);
        when(request.getRequestUrl()).thenReturn("http://example.com?a=1");
        when(request.getMethod()).thenReturn("GET");

        OAuthMessageSigner signer = mock(HmacSha1MessageSigner.class);
        consumer.setMessageSigner(signer);

        HttpParameters params = new HttpParameters();
        params.put("oauth_callback", "oob");
        consumer.setAdditionalParameters(params);

        consumer.sign(request);

        // verify that all custom params are properly read and passed to the
        // message signer
        ArgumentMatcher<HttpParameters> hasParameters = new ArgumentMatcher<HttpParameters>() {
            public boolean matches(Object argument) {
                HttpParameters params = (HttpParameters)argument;
                assertEquals("oob", params.getFirst("oauth_callback"));
                assertEquals("1", params.getFirst("a"));
                return true;
            }
        };

        verify(signer).sign(same(request), argThat(hasParameters));
    }

    @Test
    public void shouldPercentEncodeOAuthParameters() throws Exception {
        OAuthConsumer consumer = buildConsumer("1%2", CONSUMER_SECRET, null);
        consumer.setTokenWithSecret("3 4", TOKEN_SECRET);

        consumer.sign(httpGetMock);

        verify(httpGetMock).setHeader(eq("Authorization"), argThat(new HasValuesPercentEncoded()));
    }

    @Test
    public void shouldBeSerializable() throws Exception {
        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);
        consumer.setTokenWithSecret(TOKEN, TOKEN_SECRET);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream ostream = new ObjectOutputStream(baos);
        ostream.writeObject(consumer);

        ObjectInputStream istream = new ObjectInputStream(new ByteArrayInputStream(baos
                .toByteArray()));
        consumer = (OAuthConsumer)istream.readObject();

        assertEquals(CONSUMER_KEY, consumer.getConsumerKey());
        assertEquals(CONSUMER_SECRET, consumer.getConsumerSecret());
        assertEquals(TOKEN, consumer.getToken());
        assertEquals(TOKEN_SECRET, consumer.getTokenSecret());

        // signing messages should still work
        consumer.sign(httpGetMock);
    }

    @Test
    public void shouldAddBodyHashToPost() throws Exception {
        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);
        consumer.setShouldSignBody(true);
        InputStream in = new ByteArrayInputStream("Hello World!".getBytes());
        when(httpPostMock.getMessagePayload()).thenReturn(in);

        when(httpPostMock.getContentType()).thenReturn(OAuth.FORM_ENCODED);
        consumer.sign(httpPostMock);
        assertNull(consumer.getRequestParameters().getFirst(OAuth.OAUTH_BODY_HASH));

        in.reset();
        when(httpPostMock.getContentType()).thenReturn("");
        consumer.sign(httpPostMock);
        assertEquals("Lve95gjOVATpfV8EL5X4nxwjKHE=", consumer.getRequestParameters().getFirst(
                OAuth.OAUTH_BODY_HASH));
    }
    
    @Test
    public void shouldNotAddBodyHashToGet() throws Exception {
        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);
        consumer.setShouldSignBody(true);
        consumer.sign(httpGetMock);
        assertNull(consumer.getRequestParameters().getFirst(OAuth.OAUTH_BODY_HASH));
    }
    
    @Test
    public void shouldReturnCorrectBodyHashAndSignature() throws Exception {
        OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET, null);
        consumer.setShouldSignBody(true);
        consumer.sign(httpPutMock);
        verify(httpPutMock).setHeader(eq("Authorization"), argThat(new ArgumentMatcher<String>() {
            @Override
            public boolean matches(Object argument) {
                String oauthHeader = (String)argument;
                assertTrue(oauthHeader.startsWith("OAuth "));
                assertTrue(oauthHeader.contains(OAuth.OAUTH_BODY_HASH));
                return true;
            }
        }));

    }

    // @Test
    // public void shouldSupport2LeggedOAuth() throws Exception {
    // OAuthConsumer consumer = buildConsumer(CONSUMER_KEY, CONSUMER_SECRET,
    // null);
    //
    // // note how we do not set a token and secret; should still include the
    // // oauth_token parameter
    //
    // consumer.sign(httpGetMock);
    //
    // verify(httpGetMock).setHeader(eq("Authorization"),
    // argThat(new IsCompleteListOfOAuthParameters()));
    // }

    private class IsCompleteListOfOAuthParameters extends ArgumentMatcher<String> {

        @Override
        public boolean matches(Object argument) {
            String oauthHeader = (String)argument;
            assertTrue(oauthHeader.startsWith("OAuth "));
            assertAllOAuthParametersExist(OAuth.oauthHeaderToParamsMap(oauthHeader));
            return true;
        }
    }

    private void assertAllOAuthParametersExist(HttpParameters params) {
        assertNotNull(params.getFirst("oauth_consumer_key"));
        assertNotNull(params.getFirst("oauth_token"));
        assertNotNull(params.getFirst("oauth_signature_method"));
        assertNotNull(params.getFirst("oauth_signature"));
        assertNotNull(params.getFirst("oauth_timestamp"));
        assertNotNull(params.getFirst("oauth_nonce"));
        assertNotNull(params.getFirst("oauth_version"));
    }

    private class HasValuesPercentEncoded extends ArgumentMatcher<String> {

        @Override
        public boolean matches(Object argument) {
            String oauthHeader = (String)argument;
            HttpParameters params = OAuth.oauthHeaderToParamsMap(oauthHeader);
            assertEquals("\"1%252\"", params.getFirst("oauth_consumer_key"));
            assertEquals("\"3%204\"", params.getFirst("oauth_token"));
            return true;
        }
    }
}

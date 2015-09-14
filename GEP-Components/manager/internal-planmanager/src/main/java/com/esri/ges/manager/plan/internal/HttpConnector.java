package com.esri.ges.manager.plan.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

public class HttpConnector {

	final private static Log LOG = LogFactory.getLog( HttpConnector.class );
	public String createHttpRequest(

	String url, String method, 
			String postBodyType, String postBody,
			int timeOut) throws Exception {

		/*
		 * if(timeOut < 1) { timeOut = 1000 * 60 * 60; } else { timeOut =
		 * timeOut * 1000; }
		 */

		int connectiontimeout = timeOut; // 1 second
		int sockettimeout = timeOut;

		HttpParams httpparameters = new BasicHttpParams();

		HttpConnectionParams.setConnectionTimeout(httpparameters,
				connectiontimeout);
		HttpConnectionParams.setSoTimeout(httpparameters, sockettimeout * 10);

		HttpClient httpClient = new DefaultHttpClient(httpparameters);

		HttpRequestBase request = null;

		if (Val.chkStr(method).toLowerCase().equals("get")) {
			request = new HttpGet(url);

		} else {
			request = new HttpPost(url);
			HttpEntity entity = new StringEntity(postBody);
			((HttpPost) request).setEntity(entity);
			request.setHeader(HttpHeaders.CONTENT_TYPE, postBodyType);
		}
		// request.setConfig(requestConfig);
		request.setHeader(new BasicHeader("User-Agent", "ESRI - HRSD - Client"));
		LOG.info("Sending request to " + url + ", postBody = " + postBody);
		HttpResponse response = httpClient.execute(request);
		// context.setHttpRequest(request);
		if (response == null) {
			LOG.error("response = null from " + url + "postbody = " + postBody);
			throw new Exception ("Response is null " + url + " " + postBody);
		} 
		if(response.getStatusLine().getStatusCode() != 200) {
			throw new Exception("Status code = " + 
		      response.getStatusLine().getStatusCode() + 
		      ", Status reason " + response.getStatusLine().getReasonPhrase() + 
		      ", url = " + url + ", postbody = " + postBody);
		}
		
		return readResponseBody(response);
}
	
 public String readResponseBody(HttpResponse response) throws Exception {
	    HttpEntity entity = response.getEntity();
	    byte output[] = null;
	    if (entity != null) {
	      if (entity.getContentEncoding() != null) {
	        if (entity.getContentEncoding().getValue().equals("gzip")) {
	          output = unpackRaw(EntityUtils.toByteArray(entity));
	        } else {
	          output = EntityUtils.toByteArray(entity);
	        }
	      } else {
	        output = EntityUtils.toByteArray(entity);
	      }
	    }
	    return new String(output);
  }	
 
  private byte[] unpackRaw(byte[] b) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    ByteArrayInputStream bais = new ByteArrayInputStream(b);

    GZIPInputStream zis = new GZIPInputStream(bais);
    try {
      byte[] tmpBuffer = new byte[256];
      int n;
      while ((n = zis.read(tmpBuffer)) >= 0) {
        baos.write(tmpBuffer, 0, n);
      }
    } finally {
      try {
        zis.close();
      } catch (Throwable e) {
        LOG.error("Error closing in unpack raw", e);
      }
    }

    return baos.toByteArray();
  }
}

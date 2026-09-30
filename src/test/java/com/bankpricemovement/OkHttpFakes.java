package com.bankpricemovement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Test helper: an {@link OkHttpClient} whose every call is answered with HTTP 200 and an empty body, inline on the
 * calling thread, so a test that wants the network to be fine does not have to set the harness of the client tests up.
 */
final class OkHttpFakes
{
	private OkHttpFakes()
	{
	}

	/** A client whose every {@code enqueue} ends in {@code onResponse} with a 200 and a {@code Date} header. */
	static OkHttpClient answeringEverything()
	{
		final OkHttpClient http = mock(OkHttpClient.class);
		when(http.newCall(any(Request.class))).thenAnswer(invocation ->
		{
			final Request request = invocation.getArgument(0);
			final Call call = mock(Call.class);
			doAnswer(enqueued ->
			{
				final Callback callback = enqueued.getArgument(0);
				callback.onResponse(call, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
					.message("OK").header("Date", "Wed, 30 Sep 2026 14:05:04 GMT")
					.body(ResponseBody.create(MediaType.parse("application/json"), "")).build());
				return null;
			}).when(call).enqueue(any(Callback.class));
			return call;
		});
		return http;
	}
}

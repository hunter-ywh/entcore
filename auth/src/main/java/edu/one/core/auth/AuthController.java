package edu.one.core.auth;

import static edu.one.core.auth.oauth.OAuthAuthorizationResponse.code;
import static edu.one.core.auth.oauth.OAuthAuthorizationResponse.invalidRequest;
import static edu.one.core.auth.oauth.OAuthAuthorizationResponse.invalidScope;
import static edu.one.core.auth.oauth.OAuthAuthorizationResponse.serverError;
import static edu.one.core.auth.oauth.OAuthAuthorizationResponse.unauthorizedClient;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;

import jp.eisbahn.oauth2.server.async.Handler;
import jp.eisbahn.oauth2.server.data.DataHandler;
import jp.eisbahn.oauth2.server.data.DataHandlerFactory;
import jp.eisbahn.oauth2.server.endpoint.Token;
import jp.eisbahn.oauth2.server.endpoint.Token.Response;
import jp.eisbahn.oauth2.server.fetcher.clientcredential.ClientCredentialFetcher;
import jp.eisbahn.oauth2.server.fetcher.clientcredential.ClientCredentialFetcherImpl;
import jp.eisbahn.oauth2.server.granttype.GrantHandlerProvider;
import jp.eisbahn.oauth2.server.granttype.impl.DefaultGrantHandlerProvider;
import jp.eisbahn.oauth2.server.models.AuthInfo;
import jp.eisbahn.oauth2.server.models.Request;

import org.vertx.java.core.Vertx;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import edu.one.core.auth.oauth.HttpServerRequestAdapter;
import edu.one.core.auth.oauth.OAuthDataHandler;
import edu.one.core.auth.oauth.OAuthDataHandlerFactory;
import edu.one.core.infra.Controller;
import edu.one.core.infra.MongoDb;
import edu.one.core.infra.Neo;
import edu.one.core.infra.request.CookieUtils;
import edu.one.core.infra.security.UserUtils;
import edu.one.core.infra.security.resources.UserInfos;
import edu.one.core.security.ActionType;
import edu.one.core.security.SecuredAction;

public class AuthController extends Controller {

	private final DataHandlerFactory oauthDataFactory;
	private final Token token;
	private static final String USERINFO_SCOPE = "userinfo";

	public AuthController(Vertx vertx, Container container, RouteMatcher rm,
			Map<String, edu.one.core.infra.security.SecuredAction> securedActions) {
		super(vertx, container, rm, securedActions);
		this.oauthDataFactory = new OAuthDataHandlerFactory(
				new Neo(eb, log),
				new MongoDb(eb, container.config()
						.getString("mongo.address", "wse.mongodb.persistor")));
		GrantHandlerProvider grantHandlerProvider = new DefaultGrantHandlerProvider();
		ClientCredentialFetcher clientCredentialFetcher = new ClientCredentialFetcherImpl();
		this.token = new Token();
		this.token.setDataHandlerFactory(oauthDataFactory);
		this.token.setGrantHandlerProvider(grantHandlerProvider);
		this.token.setClientCredentialFetcher(clientCredentialFetcher);
	}

	public void authorize(final HttpServerRequest request) {
		final String responseType = request.params().get("response_type");
		final String clientId = request.params().get("client_id");
		final String redirectUri = request.params().get("redirect_uri");
		final String scope = request.params().get("scope");
		final String state = request.params().get("state");
		if ("code".equals(responseType) && clientId != null && !clientId.trim().isEmpty()) {
			if (USERINFO_SCOPE.equals(scope)) {
				final DataHandler data = oauthDataFactory.create(new HttpServerRequestAdapter(request));
				data.validateClientById(clientId, new Handler<Boolean>() {

					@Override
					public void handle(Boolean clientValid) {
						if (Boolean.TRUE.equals(clientValid)) {
							UserUtils.getUserInfos(eb, request, new org.vertx.java.core.Handler<UserInfos>() {

								@Override
								public void handle(UserInfos user) {
									if (user != null && user.getUserId() != null) {
										((OAuthDataHandler) data).createOrUpdateAuthInfo(
												clientId, user.getUserId(), scope, redirectUri,
												new Handler<AuthInfo>() {

													@Override
													public void handle(AuthInfo auth) {
														if (auth != null) {
															code(request, redirectUri, auth.getCode(), state);
														} else {
															serverError(request, redirectUri, state);
														}
													}
												});
									} else {
										viewLogin(request, null, request.uri());
									}
								}
							});
						} else {
							unauthorizedClient(request, redirectUri, state);
						}
					}
				});
			} else {
				invalidScope(request, redirectUri, state);
			}
		} else {
			invalidRequest(request, redirectUri, state);
		}
	}

	public void token(final HttpServerRequest request) {
		request.expectMultiPart(true);
		request.endHandler(new VoidHandler() {

			@Override
			protected void handle() {
				Request req = new HttpServerRequestAdapter(request);
				token.handleRequest(req, new Handler<Response>() {

					@Override
					public void handle(Response response) {
						renderJson(request, new JsonObject(response.getBody()), response.getCode());
					}
				});
			}
		});
	}

	private void viewLogin(HttpServerRequest request, String error, String callBack) {
		JsonObject context = new JsonObject();
		if (callBack != null && !callBack.trim().isEmpty()) {
			try {
				context.putString("callBack", URLEncoder.encode(callBack, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage(), e);
			}
		}
		if (error != null && !error.trim().isEmpty()) {
			context.putObject("error", new JsonObject().putString("message", error));
		}
		renderView(request, context, "login.html", null);
	}

	public void login(HttpServerRequest request) {
		viewLogin(request, null, request.params().get("callBack"));
	}

	public void loginSubmit(final HttpServerRequest request) {
		request.expectMultiPart(true);
		request.endHandler(new VoidHandler() {
			@Override
			public void handle() {
				String c = request.formAttributes().get("callBack");
				final StringBuilder callBack = new StringBuilder();
				if (c != null && !c.trim().isEmpty()) {
					try {
						callBack.append(URLDecoder.decode(c,"UTF-8"));
					} catch (UnsupportedEncodingException ex) {
						log.error(ex.getMessage(), ex);
						callBack.append(container.config()
								.getObject("authenticationServer").getString("loginCallback"));
					}
				} else {
					callBack.append(container.config()
							.getObject("authenticationServer").getString("loginCallback"));
				}
				DataHandler data = oauthDataFactory.create(new HttpServerRequestAdapter(request));
				String login = request.formAttributes().get("email");
				String password = request.formAttributes().get("password");
				data.getUserId(login, password, new Handler<String>() {

					@Override
					public void handle(String userId) {
						if (userId != null && !userId.trim().isEmpty()) {
							UserUtils.createSession(eb, userId,
									new org.vertx.java.core.Handler<String>() {

								@Override
								public void handle(String sessionId) {
									if (sessionId != null && !sessionId.trim().isEmpty()) {
										CookieUtils.set("oneSessionId", sessionId, request.response());
										redirect(request, callBack.toString(), "");
									} else {
										viewLogin(request, "auth.error.authenticationFailed", callBack.toString());
									}
								}
							});
						} else {
							viewLogin(request, "auth.error.authenticationFailed", callBack.toString());
						}
					}
				});

			}
		});
	}

	public void logout(final HttpServerRequest request) {
		String sessionId = CookieUtils.get("oneSessionId", request);
		String c = request.params().get("callback");
		final StringBuilder callback = new StringBuilder();
		if (c != null && !c.trim().isEmpty()) {
			try {
				callback.append(URLDecoder.decode(c, "UTF-8"));
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage(), e);
				callback.append("/login");
			}
		} else {
			callback.append("/login");
		}
		UserUtils.deleteSession(eb, sessionId, new org.vertx.java.core.Handler<Boolean>() {

			@Override
			public void handle(Boolean deleted) {
				if (Boolean.TRUE.equals(deleted)) {
					CookieUtils.set("oneSessionId", "", request.response());
				}
				redirect(request, callback.toString(), "");
			}
		});
	}

}

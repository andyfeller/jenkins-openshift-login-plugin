/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * Copyright (c) 2016, Red Hat, Inc., Clayton Coleman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.openshift.jenkins.plugins.openshiftlogin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.acegisecurity.Authentication;
import org.acegisecurity.AuthenticationException;
import org.acegisecurity.AuthenticationManager;
import org.acegisecurity.BadCredentialsException;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.context.SecurityContextHolder;
import org.acegisecurity.providers.UsernamePasswordAuthenticationToken;
import org.acegisecurity.providers.anonymous.AnonymousAuthenticationToken;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.Header;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.openidconnect.IdTokenResponse;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.SecurityUtils;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.User;
import hudson.model.View;
import hudson.scm.SCM;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import hudson.security.SecurityRealm;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.Secret;
import jenkins.model.Jenkins;

/**
 * Login with OpenShift using OpenID Connect / OAuth 2
 *
 */
public class OpenShiftOAuth2SecurityRealm extends SecurityRealm {
    static final Logger LOGGER = Logger
            .getLogger(OpenShiftOAuth2SecurityRealm.class.getName());

    /**
     * OAuth 2 scope. This is enough to call a variety of userinfo api's.
     */
    private static final String SCOPE_INFO = "user:info";
    private static final String SCOPE_CHECK_ACCESS = "user:check-access";

    static final String DEFAULT_SVC_ACCT_DIR = "/run/secrets/kubernetes.io/serviceaccount";
    static final String DEFAULT_SVR_PREFIX = "https://openshift.default.svc";
    static final String NAMESPACE = "namespace";
    private static final String TOKEN = "token";
    private static final String CA_CRT = "ca.crt";
    private static final String FINISH_METHOD = "doFinishLogin";
    private static final String START_METHOD = "doCommenceLogin";
    private static final String DISPLAY_NAME = "Login with OpenShift";
    private static final String LOGIN_URL = "securityRealm/commenceLogin";

    private static final String[] ROLES = new String[] { "admin", "edit",
            "view" };

    private static final String USER_URI = "/oapi/v1/users/~";
    private static final String SAR_URI = "/oapi/v1/subjectaccessreviews";
    private static final String OAUTH_PROVIDER_URI = "/.well-known/oauth-authorization-server";

    private static final String K8S_HOST_ENV_VAR = "KUBERNETES_SERVICE_HOST";
    private static final String K8S_PORT_ENV_VAR = "KUBERNETES_SERVICE_PORT";

    private static final String LOGOUT = "logout";

    static final String LOGGING_OUT = "loggingOut";

    /**
     * Global instance of the JSON factory.
     */
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();

    static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

    private static final Object USER_UPDATE_LOCK = new Object();

    /**
     * Control the redirection URL for this realm. Exposed for testing.
     */
    static String redirectUrl;
    /**
     * Allow a custom transport to be injected. Exposed for testing.
     */
    static HttpTransport testTransport;

    /**
     * The transport loaded with a service account secret.
     * Marked static for https://jenkins.io/blog/2018/03/15/jep-200-lts/#after-the-upgrade
     * Will get repopulated on restart
     */
    private static HttpTransport transport;

    /**
     * The service account directory, if set, instructs the plugin to follow the
     * Kubernetes service account conventions to locate the service account
     * token (client secret) and namespace value (to build the service account).
     */
    private final String serviceAccountDirectory;

    /**
     * If the user did not specify a specific value for serviceAccountDirectory,
     * the inferred default is stored here
     */
    private String defaultedServiceAccountDirectory;

    /**
     * The service account name is used when serviceAccountDirectory is set to
     * create the client ID (system:serviceaccount:NAMESPACE:NAME).
     */
    private final String serviceAccountName;

    /**
     * If the user did not specify a specific value for serviceAccountName, the
     * inferred default is stored here
     */
    private String defaultedServiceAccountName;

    /**
     * The base part of the OpenShift URL for making API calls.
     */
    private final String serverPrefix;

    /**
     * If the user did not specify a specific value for serverPrefix, the
     * inferred default is stored here
     */
    private String defaultedServerPrefix;

    /**
     * The URL to the API server the browser will use during redirects.
     */
    private final String redirectURL;

    /**
     * If the user did not specify a specific value for redirectURL, the
     * inferred default is stored here.
     */
    private String defaultedRedirectURL;

    /**
     * The clientID from the OpenShift server.
     */
    private final String clientId;

    /**
     * If the user did not specify a specific value for defaultedClientId, the
     * inferred default is stored here
     */
    private String defaultedClientId;

    /**
     * The client secret from the OpenShift server.
     */
    private final Secret clientSecret;

    /**
     * If the user did not specify a specific value for defaultedClientSecret,
     * the inferred default is stored here
     */
    private String defaultedClientSecret;

    /**
     * The project/namespace of the serviceaccount for the jenkins pod
     */
    private String namespace;

    /**
     * The oauth provider info retrieved from the master
     */
    private OpenShiftProviderInfo provider;

    private OpenShiftPermissionFilter filter;

    @DataBoundConstructor
    public OpenShiftOAuth2SecurityRealm(String serviceAccountDirectory,
            String serviceAccountName, String serverPrefix, String clientId,
            String clientSecret, String redirectURL) throws IOException,
            GeneralSecurityException {
        HttpTransport transport = HTTP_TRANSPORT;

        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(String
                    .format("ctor: incoming args sa dir %s sa name %s svr prefix %s client id %s client secret %s redirectURL %s",
                            serviceAccountDirectory, serviceAccountName,
                            serverPrefix, clientId, clientSecret, redirectURL));

        String fixedServiceAccountDirectory = Util
                .fixEmpty(serviceAccountDirectory);
        this.clientId = Util.fixEmpty(clientId);
        if (Util.fixEmpty(clientSecret) != null)
            this.clientSecret = Secret.fromString(clientSecret);
        else
            this.clientSecret = null;

        this.defaultedServerPrefix = DEFAULT_SVR_PREFIX;
        this.serverPrefix = Util.fixEmpty(serverPrefix);

        this.redirectURL = Util.fixEmpty(redirectURL);

        this.defaultedServiceAccountDirectory = DEFAULT_SVC_ACCT_DIR;
        this.serviceAccountDirectory = fixedServiceAccountDirectory;

        this.serviceAccountName = Util.fixEmpty(serviceAccountName);

        OpenShiftOAuth2SecurityRealm.transport = transport;

        if (testTransport != null)
            OpenShiftOAuth2SecurityRealm.transport = testTransport;
        else
            populateDefaults();

        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine(String
                    .format("ctor: derived default client id %s client secret %s sa dir %s transport %s",
                            defaultedClientId, defaultedClientSecret,
                            defaultedServiceAccountDirectory, transport));
    }

    /*
     * Note, a fair amount of investigation was done into leveraging the
     * hudson.security.SecurityRealm.createFilter(FilterConfig) extension point.
     * However, quite a bit of tight coupling with assumed behavior in the
     * jenkins core wrt the other security features and servlet filtering arose.
     * Hence, we are sticking with the
     * hudson.util.PluginServletFilter.addFilter(Filter) path.
     */
    synchronized void createFilter() {
        // restarts on things like plugin upgrade bypassed the call to the
        // constructor, so filter initialization
        // has to be driven in-line; note, after initial bring up, the filter
        // variable will be set after subsequent
        // jenkins restarts, but the addFilter call needs to be made on each
        // restart, so we check flag to see if the filter
        // has been ran through at least once
        if (filter == null || !filter.initCalled) {
            try {
                filter = new OpenShiftPermissionFilter();
                hudson.util.PluginServletFilter.addFilter(filter);
            } catch (ServletException e) {
                LOGGER.log(Level.SEVERE, "createFilter", e);
            }
        }
    }

    boolean populateDefaults() throws IOException, GeneralSecurityException {
        createFilter();
        boolean runningInOpenShiftPodWithRequiredOAuthFeatures = EnvVars.masterEnvVars
                .get(K8S_HOST_ENV_VAR) != null
                && EnvVars.masterEnvVars.get(K8S_PORT_ENV_VAR) != null;
        // we want to be verbose wrt error logging if we are at least running
        // within a pod ... but if we know we are outside a pod, only
        // log if trace enabled
        boolean withinAPod = runningInOpenShiftPodWithRequiredOAuthFeatures
                || (new File(getDefaultedServiceAccountDirectory())).exists();

        FileInputStream fis = null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(new File(
                    getDefaultedServiceAccountDirectory(), NAMESPACE)));
            namespace = br.readLine();
            runningInOpenShiftPodWithRequiredOAuthFeatures = runningInOpenShiftPodWithRequiredOAuthFeatures
                    && (namespace != null ? namespace.length() > 0 : false);
            br = new BufferedReader(new FileReader(new File(
                    getDefaultedServiceAccountDirectory(), TOKEN)));
            defaultedClientSecret = br.readLine();
            runningInOpenShiftPodWithRequiredOAuthFeatures = runningInOpenShiftPodWithRequiredOAuthFeatures
                    && (defaultedClientSecret != null ? defaultedClientSecret
                            .length() > 0 : false);
            fis = new FileInputStream(new File(
                    getDefaultedServiceAccountDirectory(), CA_CRT));
            KeyStore keyStore = SecurityUtils.getDefaultKeyStore();
            try {
                keyStore.size();
            } catch (KeyStoreException e) {
                keyStore.load(null);
            }
            SecurityUtils.loadKeyStoreFromCertificates(keyStore,
                    SecurityUtils.getX509CertificateFactory(), fis);
            transport = new NetHttpTransport.Builder().trustCertificates(
                    keyStore).build();
        } catch (FileNotFoundException e) {
            runningInOpenShiftPodWithRequiredOAuthFeatures = false;
            if (LOGGER.isLoggable(Level.FINE) || withinAPod)
                LOGGER.log(Level.FINE, "populatateDefaults", e);
        } finally {
            if (fis != null)
                fis.close();
            if (br != null)
                br.close();
        }

        final Credential credential = new Credential(
                BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(getDefaultedClientSecret().getPlainText());
        try {
            OpenShiftUserInfo user = getOpenShiftUserInfo(credential, transport);
            String[] userNameParts = user.getName().split(":");
            if (userNameParts != null && userNameParts.length == 4) {
                defaultedServiceAccountName = userNameParts[3];
            }
            runningInOpenShiftPodWithRequiredOAuthFeatures = runningInOpenShiftPodWithRequiredOAuthFeatures
                    && (defaultedServiceAccountName != null ? defaultedServiceAccountName
                            .length() > 0 : false);
            defaultedClientId = "system:serviceaccount:" + namespace + ":"
                    + getDefaultedServiceAccountName();

            provider = getOpenShiftOAuthProvider(credential, transport);
            if (withinAPod)
                LOGGER.info(String.format("OpenShift OAuth: provider: %s",
                        provider));
            if (provider != null) {
                // the issuer is the public address of the k8s svc; use this vs.
                // the hostname or ip/port that is only available within the
                // cluster
                this.defaultedRedirectURL = provider.issuer;
            } else {
                runningInOpenShiftPodWithRequiredOAuthFeatures = false;
            }
        } catch (Throwable t) {
            runningInOpenShiftPodWithRequiredOAuthFeatures = false;
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "populateDefaults", t);
            else if (withinAPod)
                LOGGER.log(Level.INFO, "populateDefaults", t);
        }

        if (!runningInOpenShiftPodWithRequiredOAuthFeatures) {
            boolean hasSAName = this.serviceAccountName != null
                    || this.defaultedServiceAccountName != null;
            boolean hasSecret = this.clientSecret != null
                    || this.defaultedClientSecret != null;
            boolean hasClientID = this.clientId != null
                    || this.defaultedClientId != null;
            boolean hasClientSecret = this.clientSecret != null
                    || this.defaultedClientSecret != null;
            boolean hasRedirectURL = this.redirectURL != null
                    || this.defaultedRedirectURL != null;
            // namespace check is really the validation that the service account
            // directory is OK
            if (this.namespace != null && hasSAName && hasSecret && hasClientID
                    && hasClientSecret && hasRedirectURL)
                runningInOpenShiftPodWithRequiredOAuthFeatures = true;

        }

        if (withinAPod)
            LOGGER.info(String
                    .format("OpenShift OAuth returning %s with namespace %s SA dir %s default %s SA name %s default %s client ID %s default %s secret %s default %s redirect %s default %s server %s default %s",
                            runningInOpenShiftPodWithRequiredOAuthFeatures,
                            this.namespace, this.serviceAccountDirectory,
                            this.defaultedServiceAccountDirectory,
                            this.serviceAccountName,
                            this.defaultedServiceAccountName, this.clientId,
                            this.defaultedClientId, (Secret.toString(this.clientSecret).length() > 6 ? Secret.toString(this.clientSecret).substring(0, 5) + "......." : "null"),
                            (this.defaultedClientSecret != null && this.defaultedClientSecret.length() > 6 ? this.defaultedClientSecret.substring(0, 5) + "......." : "null"), this.redirectURL,
                            this.defaultedRedirectURL, this.serverPrefix,
                            this.defaultedServerPrefix));

        return runningInOpenShiftPodWithRequiredOAuthFeatures;
    }

    public String getServiceAccountDirectory() {
        return serviceAccountDirectory;
    }

    public String getDefaultedServiceAccountDirectory() {
        if (getServiceAccountDirectory() == null) {
            return defaultedServiceAccountDirectory;
        }
        return getServiceAccountDirectory();
    }

    public String getServiceAccountName() {
        return serviceAccountName;
    }

    public String getDefaultedServiceAccountName() {
        if (getServiceAccountName() == null) {
            return defaultedServiceAccountName;
        }
        return getServiceAccountName();
    }

    public String getServerPrefix() {
        return serverPrefix;
    }

    public String getDefaultedServerPrefix() {
        if (getServerPrefix() == null)
            return defaultedServerPrefix;
        return getServerPrefix();
    }

    public String getRedirectURL() {
        return redirectURL;
    }

    public String getDefaultedRedirectURL() {
        if (getRedirectURL() == null)
            return defaultedRedirectURL;
        return getRedirectURL();
    }

    public String getClientId() {
        return clientId;
    }

    public String getDefaultedClientId() {
        if (getClientId() == null) {
            return defaultedClientId;
        }
        return getClientId();
    }

    public Secret getClientSecret() {
        return clientSecret;
    }

    public Secret getDefaultedClientSecret() {
        if (getClientSecret() == null) {
            return Secret.fromString(defaultedClientSecret);
        }
        return getClientSecret();
    }

    public String getDefaultedNamespace() {
        return namespace;
    }

    /**
     * Login begins with our {@link #doCommenceLogin(String,String)} method.
     */
    @Override
    public String getLoginUrl() {
        return LOGIN_URL;
    }

    private OpenShiftProviderInfo getOpenShiftOAuthProvider(
            final Credential credential, final HttpTransport transport)
            throws IOException {
        HttpRequestFactory requestFactory = transport
                .createRequestFactory(new HttpRequestInitializer() {
                    public void initialize(HttpRequest request)
                            throws IOException {
                        credential.initialize(request);
                        request.setParser(new JsonObjectParser(JSON_FACTORY));
                    }
                });
        GenericUrl url = new GenericUrl(getDefaultedServerPrefix()
                + OAUTH_PROVIDER_URI);

        HttpRequest request = requestFactory.buildGetRequest(url);

        OpenShiftProviderInfo info = request.execute().parseAs(
                OpenShiftProviderInfo.class);
        return info;
    }

    private OpenShiftUserInfo getOpenShiftUserInfo(final Credential credential,
            final HttpTransport transport) throws IOException {
        HttpRequestFactory requestFactory = transport
                .createRequestFactory(new HttpRequestInitializer() {
                    public void initialize(HttpRequest request)
                            throws IOException {
                        credential.initialize(request);
                        request.setParser(new JsonObjectParser(JSON_FACTORY));
                    }
                });
        GenericUrl url = new GenericUrl(getDefaultedServerPrefix() + USER_URI);

        HttpRequest request = requestFactory.buildGetRequest(url);

        OpenShiftUserInfo info = request.execute().parseAs(
                OpenShiftUserInfo.class);
        return info;
    }

    private String buildSARJson(String namespace, String verb)
            throws IOException {
        OpenShiftSubjectAccessReviewRequest request = new OpenShiftSubjectAccessReviewRequest();
        request.namespace = namespace;
        request.verb = verb;
        String json = JSON_FACTORY.toString(request);
        return json;
    }

    private HttpRequest buildPostSARRequest(HttpRequestFactory requestFactory,
            GenericUrl url, final String json) throws IOException {
        HttpContent contentAdmin = new HttpContent() {

            @Override
            public long getLength() throws IOException {
                return (long) (json.getBytes().length);
            }

            @Override
            public String getType() {
                return "application/json";
            }

            @Override
            public boolean retrySupported() {
                return false;
            }

            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(json.getBytes());
                out.flush();
            }

        };
        return requestFactory.buildPostRequest(url, contentAdmin);
    }

    private Set<String> postSAR(final Credential credential,
            final HttpTransport transport) throws IOException {
        HashSet<String> allowedVerbs = new HashSet<String>();
        HttpRequestFactory requestFactory = transport
                .createRequestFactory(new HttpRequestInitializer() {
                    public void initialize(HttpRequest request)
                            throws IOException {
                        credential.initialize(request);
                        request.setParser(new JsonObjectParser(JSON_FACTORY));
                    }
                });
        GenericUrl url = new GenericUrl(getDefaultedServerPrefix() + SAR_URI);

        for (String verb : ROLES) {
            String json = buildSARJson(namespace, verb);
            HttpRequest request = this.buildPostSARRequest(requestFactory, url,
                    json);
            OpenShiftSubjectAccessReviewResponse review = request.execute()
                    .parseAs(OpenShiftSubjectAccessReviewResponse.class);
            if (review != null) {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.fine(String
                            .format("postSAR: response for verb %s hydrated into obj: namespace %s allowed %s reason %s",
                                    verb, review.namespace,
                                    Boolean.toString(review.allowed),
                                    review.reason));
                if (review.allowed)
                    allowedVerbs.add(verb);
            }
        }
        return allowedVerbs;
    }

    /**
     * Acegi has this notion that first an
     * {@link org.acegisecurity.Authentication} object is created by collecting
     * user information and then the act of authentication is done later (by
     * {@link org.acegisecurity.AuthenticationManager}) to verify it. But in
     * case of OpenID, we create an {@link org.acegisecurity.Authentication}
     * only after we verified the user identity, so
     * {@link org.acegisecurity.AuthenticationManager} becomes no-op.
     */
    @Override
    public SecurityComponents createSecurityComponents() {
        return new SecurityComponents(new AuthenticationManager() {
            public Authentication authenticate(Authentication authentication)
                    throws AuthenticationException {
                if (authentication instanceof AnonymousAuthenticationToken)
                    return authentication;
                throw new BadCredentialsException(
                        "Unexpected authentication type: " + authentication);
            }
        });
    }

    protected OAuthSession newOAuthSession(String from,
            final String redirectOnFinish) throws MalformedURLException {
        // shout out to Cesar, while the auth server URL needs to be publicly
        // accessible such that the browser
        // can reference it, the token server URL does not; see
        // https://docs.oracle.com/cd/E50612_01/doc.11122/oauth_guide/content/oauth_flows.html
        // for a good description of the flow; as such, while the
        // OAUTH_PROVIDER_URI endpoint returns a publicly accessible token
        // server url, we
        // can't assume that is accessible from the jenkins pod (think running
        // in a publicly accessible ec2 instance where the public facing address
        // of the master is not accessible from within the cluster); so we only
        // use the configured server prefix, where if not explicitly configured
        // we go with the internally accessible default
        final GenericUrl tokenServerURL = new GenericUrl(
                getDefaultedServerPrefix() + "/oauth/token");
        final String authorizationServerURL = getDefaultedRedirectURL()
                + "/oauth/authorize";

        final AuthorizationCodeFlow flow = new AuthorizationCodeFlow.Builder(
                BearerToken.queryParameterAccessMethod(), transport,
                JSON_FACTORY, tokenServerURL,
                new ClientParametersAuthentication(getDefaultedClientId(),
                        getDefaultedClientSecret().getPlainText()),
                getDefaultedClientId(), authorizationServerURL).setScopes(
                Arrays.asList(SCOPE_INFO, SCOPE_CHECK_ACCESS)).build();

        final OpenShiftOAuth2SecurityRealm secRealm = this;
        final String url = buildOAuthRedirectUrl(redirectOnFinish);

        return new OAuthSession(flow, from, url) {
            @Override
            public HttpResponse onSuccess(String authorizationCode) {
                try {
                    IdTokenResponse response = IdTokenResponse.execute(flow
                            .newTokenRequest(authorizationCode).setRedirectUri(
                                    url));
                    final Credential credential = new Credential(
                            BearerToken.authorizationHeaderAccessMethod())
                            .setFromTokenResponse(response);
                    this.setCredential(credential);
                    secRealm.updateAuthorizationStrategy(credential);

                    return new HttpRedirect(redirectOnFinish);

                } catch (Throwable e) {
                    if (LOGGER.isLoggable(Level.FINE))
                        LOGGER.log(Level.FINE, "onSuccess", e);
                    return HttpResponses.error(500, e);
                }
            }

        };
    }

    public UsernamePasswordAuthenticationToken updateAuthorizationStrategy(
            Credential credential) throws IOException, GeneralSecurityException {
        populateDefaults();
        OpenShiftUserInfo info = getOpenShiftUserInfo(credential, transport);
        Set<String> allowedRoles = postSAR(credential, transport);
        GrantedAuthority[] authorities = new GrantedAuthority[] { SecurityRealm.AUTHENTICATED_AUTHORITY };

        // we append the role suffix to the name stored into Jenkins, since a
        // given user is able to log in at varying scope/permission
        // levels in openshift; however, for now, we make sure the display name
        // for Jenkins does not include this suffix
        String suffix = null;
        if (allowedRoles.contains("admin"))
            suffix = "-admin";
        else if (allowedRoles.contains("edit"))
            suffix = "-edit";
        else if (allowedRoles.contains("view"))
            suffix = "-view";

        // logs this user in.... with the index of
        // UsernamePasswordAuthenticationToken token being matrixKey, that will
        // tell jenkins auth
        // code down the line what permissions to map to, where there 3
        // permissions for each user possible, where the key for that permission
        // is the username appended by -admin, -edit, or -view
        // NOTE, if all three self-sars fail, where it has no permission entries
        // in the jenkins auth matrix, we don't update the security ctx,
        // and leave the user as the jenkins anonymous user; that way, a
        // malicious
        // user can't say create a "foo-admin" user, and get user foo's admin
        // permission; note, if "foo-admin" has say view access, then his
        // permission key via this token and matrixKey
        // will be "foo-admin-view", and only have the jenkins permissions we've
        // assigned to the view role
        UsernamePasswordAuthenticationToken token = null;
        if (suffix != null) {
            String matrixKey = info.getName() + suffix;
            token = new UsernamePasswordAuthenticationToken(matrixKey, "",
                    authorities);
            SecurityContextHolder.getContext().setAuthentication(token);

            User u = User.get(token.getName());
            info.updateProfile(u);
            // this controls the user name that is displayed atop the Jenkins
            // browser window;
            // we'll display the "core" user name without the admin/edit/view
            // suffix
            u.setFullName(info.getName());
            u.save();

            // So if you look at GlobalSecurityConfiguration and
            // GlobalMatrixAuthorizationStrategy (including its DescriptorImpl)
            // and the associated config.jelly files,
            // you'll see that the AuthourizationStrategy object stored in
            // Jenkins is *essentially* immutable (except for adds, with
            // comments saying only to use durin contruction),
            // and that when users manipulate
            // the panel "Configure Global Security", new instances of
            // Global/ProjectMatrixAuthorizationStrategy are created, where
            // existing users are set up again.
            // we'll mimic what the "Configure Global Security" config page does

            // NOTE, Jenkins currently does not employ any sort of
            // synchronization around their paths for updating the authorization
            // strategy;
            // However, with user login now driving the addition of users and
            // their permissions, that does not seem prudent when users are
            // logging in concurrently.

            synchronized (USER_UPDATE_LOCK) {
                GlobalMatrixAuthorizationStrategy existingAuthMgr = (GlobalMatrixAuthorizationStrategy) Jenkins
                        .getInstance().getAuthorizationStrategy();
                Set<String> usersGroups = existingAuthMgr.getGroups();

                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.fine(String
                            .format("updateAuthorizationStrategy: got users %s where this user is %s",
                                    usersGroups.toString(), info.getName()));

                if (usersGroups.contains(matrixKey)) {
                    // since we store username-maxrole in the auth matrix, we
                    // can infer that since this user-role pair already exists
                    // as a key, there is no need to update the matrix
                    // since our permissions are still the same on the openshift
                    // side
                    LOGGER.info(String
                            .format("OpenShift OAuth: user %s, stored in the matrix as %s, based on OpenShift roles %s already exists in Jenkins",
                                    info.getName(), matrixKey, allowedRoles));
                } else {
                    List<PermissionGroup> permissionGroups = new ArrayList<PermissionGroup>(
                            PermissionGroup.getAll());
                    if (LOGGER.isLoggable(Level.FINE))
                        LOGGER.fine(String.format(
                                "updateAuthorizationStrategy: permissions %s",
                                permissionGroups.toString()));

                    GlobalMatrixAuthorizationStrategy newAuthMgr = null;
                    if (existingAuthMgr instanceof ProjectMatrixAuthorizationStrategy) {
                        newAuthMgr = new ProjectMatrixAuthorizationStrategy();
                    } else {
                        newAuthMgr = new GlobalMatrixAuthorizationStrategy();
                    }

                    if (newAuthMgr != null) {
                        for (String userGroup : usersGroups) {
                            // copy any of the other users' permissions from the
                            // prior auth mgr to our new one
                            for (PermissionGroup pg : permissionGroups) {
                                for (Permission p : pg.getPermissions()) {
                                    if (existingAuthMgr.hasPermission(userGroup, p)) {
                                        newAuthMgr.add(p, userGroup);
                                    }
                                }
                            }

                        }

                        LOGGER.info(String
                                .format("OpenShift OAuth: adding permissions for user %s, stored in the matrix as %s, based on OpenShift roles %s",
                                        info.getName(), matrixKey, allowedRoles));

                        // map OpenShift user based on role to Jenkins user with
                        // analogous permissions
                        if (allowedRoles.contains("view")
                                || allowedRoles.contains("edit")
                                || allowedRoles.contains("admin")) {
                            newAuthMgr.add(Hudson.READ, matrixKey);
                            newAuthMgr.add(Item.READ, matrixKey);
                            newAuthMgr.add(Item.DISCOVER, matrixKey);
                            newAuthMgr.add(CredentialsProvider.VIEW, matrixKey);
                        }
                        if (allowedRoles.contains("edit")
                                || allowedRoles.contains("admin")) {
                            newAuthMgr.add(Item.BUILD, matrixKey);
                            newAuthMgr.add(Item.CONFIGURE, matrixKey);
                            newAuthMgr.add(Item.CREATE, matrixKey);
                            newAuthMgr.add(Item.DELETE, matrixKey);
                            newAuthMgr.add(Item.CANCEL, matrixKey);
                            newAuthMgr.add(Item.WORKSPACE, matrixKey);
                            newAuthMgr.add(SCM.TAG, matrixKey);
                            newAuthMgr.add(Jenkins.RUN_SCRIPTS, matrixKey);
                        }
                        if (allowedRoles.contains("admin")) {
                            newAuthMgr.add(Computer.CONFIGURE, matrixKey);
                            newAuthMgr.add(Computer.DELETE, matrixKey);
                            newAuthMgr.add(Hudson.ADMINISTER, matrixKey);
                            newAuthMgr.add(Hudson.READ, matrixKey);
                            newAuthMgr.add(Run.DELETE, matrixKey);
                            newAuthMgr.add(Run.UPDATE, matrixKey);
                            newAuthMgr.add(View.CONFIGURE, matrixKey);
                            newAuthMgr.add(View.CREATE, matrixKey);
                            newAuthMgr.add(View.DELETE, matrixKey);
                            newAuthMgr.add(CredentialsProvider.CREATE,
                                    matrixKey);
                            newAuthMgr.add(CredentialsProvider.UPDATE,
                                    matrixKey);
                            newAuthMgr.add(CredentialsProvider.DELETE,
                                    matrixKey);
                            newAuthMgr.add(CredentialsProvider.MANAGE_DOMAINS,
                                    matrixKey);
                        }
                        
                        Jenkins.getInstance().setAuthorizationStrategy(
                                newAuthMgr);
                        try {
                            Jenkins.getInstance().save();
                        } catch (Throwable t) {
                            // see https://jenkins.io/blog/2018/03/15/jep-200-lts/#after-the-upgrade
                            // running on 2.107 ... seen intermittent errors here, even after 
                            // marking transport transient (as the xml stuff does not use standard
                            // serialization; switch from transient instance var to static var to 
                            // attempt to avoid xml marshalling;
                            // Always logging for now, but will monitor and bracket with a FINE 
                            // logging level check if this becomes very verbose.
                            LOGGER.log(Level.INFO, "updateAuthorizationStrategy", t);
                        }
                    }
                }

            }
        }

        return token;
    }

    /**
     * The login process starts from here.
     */
    public HttpResponse doCommenceLogin(@QueryParameter String from,
            @Header("Referer") final String referer) throws IOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.entering(OpenShiftOAuth2SecurityRealm.class.getName(),
                    START_METHOD, new Object[] { from, referer });

        // refresh defaults just in case the jenkins pod was recycled, etc.
        try {
            populateDefaults();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
        final String redirectOnFinish;
        URL fromURL = null;
        URL refererURL = null;
        try {
            fromURL = new URL(from);
        } catch (MalformedURLException e) {
        }
        try {
            refererURL = new URL(referer);
        } catch (MalformedURLException e) {
        }
        if (fromURL != null) {
            redirectOnFinish = from;
        } else if (refererURL != null) {
            redirectOnFinish = referer;
        } else {
            redirectOnFinish = Jenkins.getInstance().getRootUrl();
        }

        return newOAuthSession(from, redirectOnFinish).doCommenceLogin();
    }

    private String buildOAuthRedirectUrl(String redirect)
            throws MalformedURLException {
        if (redirectUrl != null)
            return redirectUrl;
        URL url = null;
        try {
            url = new URL(redirect);
            // just in case, strip redirect to a "root" url before appending the
            // finishLogin path
            // also validate the protocol as a sanity check
            if (url != null
                    && (url.getProtocol().equalsIgnoreCase("http") || url
                            .getProtocol().equalsIgnoreCase("https"))) {
                return url.getProtocol() + "://" + url.getHost()
                        + "/securityRealm/finishLogin";
            }
        } catch (MalformedURLException e) {
            throw e;
        }
        throw new MalformedURLException("redirect url " + redirect
                + " insufficient");
    }

    /**
     * This is where the user comes back to at the end of the OpenID redirect
     * ping-pong.
     */
    public HttpResponse doFinishLogin(StaplerRequest request)
            throws IOException {
        if (LOGGER.isLoggable(Level.FINE)) {
            if (request != null)
                LOGGER.entering(OpenShiftOAuth2SecurityRealm.class.getName(),
                        FINISH_METHOD, new Object[] { request.getQueryString(),
                                request.getRequestURL() });
            else
                LOGGER.entering(OpenShiftOAuth2SecurityRealm.class.getName(),
                        FINISH_METHOD);
        }
        if (OAuthSession.getCurrent() != null) {
            return OAuthSession.getCurrent().doFinishLogin(request);
        } else {
            // if oauth session null, then came in with stale http session
            // and/or "securityRealm/finishLogin" on the browser's redirect url,
            // so redirect to root url and have them re-login, like standard
            // jenkins auth
            return new HttpRedirect(Jenkins.getInstance().getRootUrl());
        }
    }

    @Override
    protected String getPostLogOutUrl(StaplerRequest req, Authentication auth) {
        if (req.getRequestURL().toString().contains(LOGOUT))
            req.getSession().setAttribute(LOGGING_OUT, LOGGING_OUT);
        // there was a scenario when a user a) logged out of jenkins, and b)
        // jenkins was restarted,
        // where the various redirection query parameters on the logout url
        // would result in a login
        // going directly to the doFinishLogin path with no http session / oauth
        // session available;
        // forcing the user back down the doCommenceLogin path did not work for
        // various reasons, and
        // the solution above (to redirect to jenkins root) meant the user had
        // to submit the login
        // request twice to get authenticated and logged in.
        //
        // By updating the post log out url here with this Jenkins plugin point
        // (where we strip out the /logout suffix Jenkins applies
        // and return the last success url the user accessed Jenkins with, we
        // avoid the need for the
        // 2 login attempts after logout when jenkins is recycled in the
        // interim.
        return req.getRequestURL().toString().replace(LOGOUT, "");
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SecurityRealm> {

        public String getDisplayName() {
            return DISPLAY_NAME;
        }

        private FormValidation paramsWithPodDefaults(
                @QueryParameter String value) {
            if (value == null || value.length() == 0)
                return FormValidation
                        .warning("Unless you specify a value here, the assumption will be that Jenkins is running inside an OpenShift pod, where the value is available.");
            return FormValidation.ok();
        }

        public FormValidation doCheckServiceAccountDirectory(
                @QueryParameter String value) throws IOException,
                ServletException {
            return paramsWithPodDefaults(value);
        }

        public FormValidation doCheckClientId(@QueryParameter String value)
                throws IOException, ServletException {
            return paramsWithPodDefaults(value);
        }

        public FormValidation doCheckClientSecret(@QueryParameter String value)
                throws IOException, ServletException {
            return paramsWithPodDefaults(value);
        }

        public FormValidation doCheckServerPrefix(@QueryParameter String value)
                throws IOException, ServletException {
            return paramsWithPodDefaults(value);
        }

        public FormValidation doCheckRedirectURL(@QueryParameter String value)
                throws IOException, ServletException {
            return paramsWithPodDefaults(value);
        }

        public FormValidation doCheckServiceAccountName(
                @QueryParameter String value) throws IOException,
                ServletException {
            return paramsWithPodDefaults(value);
        }

    }

}

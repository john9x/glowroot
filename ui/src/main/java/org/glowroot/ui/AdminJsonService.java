/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveAggregateRepository;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.config.FatStorageConfig;
import org.glowroot.storage.config.ImmutableFatStorageConfig;
import org.glowroot.storage.config.ImmutableLdapConfig;
import org.glowroot.storage.config.ImmutableServerStorageConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableUserConfig;
import org.glowroot.storage.config.ImmutableWebConfig;
import org.glowroot.storage.config.LdapConfig;
import org.glowroot.storage.config.RoleConfig;
import org.glowroot.storage.config.ServerStorageConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.UserConfig;
import org.glowroot.storage.config.WebConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ConfigRepository.OptimisticLockException;
import org.glowroot.storage.repo.RepoAdmin;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.storage.util.Encryption;
import org.glowroot.storage.util.MailService;
import org.glowroot.ui.HttpServer.PortChangeFailedException;
import org.glowroot.ui.LdapAuthentication.AuthenticationException;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PRECONDITION_FAILED;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@JsonService
class AdminJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final boolean fat;
    private final ConfigRepository configRepository;
    private final RepoAdmin repoAdmin;
    private final LiveAggregateRepository liveAggregateRepository;
    private final MailService mailService;

    private volatile @MonotonicNonNull HttpServer httpServer;

    AdminJsonService(boolean fat, ConfigRepository configRepository, RepoAdmin repoAdmin,
            LiveAggregateRepository liveAggregateRepository, MailService mailService) {
        this.fat = fat;
        this.configRepository = configRepository;
        this.repoAdmin = repoAdmin;
        this.liveAggregateRepository = liveAggregateRepository;
        this.mailService = mailService;
    }

    void setHttpServer(HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    // all users have permission to change their own password
    @POST(path = "/backend/change-password", permission = "")
    String changePassword(@BindRequest ChangePassword changePassword,
            @BindCaseAmbiguousUsername String caseAmbiguousUsername) throws Exception {
        UserConfig userConfig =
                configRepository.getUserConfigCaseInsensitive(caseAmbiguousUsername);
        checkNotNull(userConfig, "user no longer exists");
        if (!PasswordHash.validatePassword(changePassword.currentPassword(),
                userConfig.passwordHash())) {
            return "{\"currentPasswordIncorrect\":true}";
        }
        ImmutableUserConfig updatedUserConfig = ImmutableUserConfig.builder().copyFrom(userConfig)
                .passwordHash(PasswordHash.createHash(changePassword.newPassword()))
                .build();
        configRepository.updateUserConfig(updatedUserConfig, userConfig.version());
        return "";
    }

    @GET(path = "/backend/admin/web", permission = "admin:view:web")
    String getWebConfig() throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        return getWebConfig(false);
    }

    @GET(path = "/backend/admin/storage", permission = "admin:view:storage")
    String getStorageConfig() throws Exception {
        if (fat) {
            FatStorageConfig config = configRepository.getFatStorageConfig();
            return mapper.writeValueAsString(FatStorageConfigDto.create(config));
        } else {
            ServerStorageConfig config = configRepository.getServerStorageConfig();
            return mapper.writeValueAsString(ServerStorageConfigDto.create(config));
        }
    }

    @GET(path = "/backend/admin/smtp", permission = "admin:view:smtp")
    String getSmtpConfig() throws Exception {
        SmtpConfig config = configRepository.getSmtpConfig();
        String localServerName = InetAddress.getLocalHost().getHostName();
        return mapper.writeValueAsString(ImmutableSmtpConfigResponse.builder()
                .config(SmtpConfigDto.create(config))
                .localServerName(localServerName)
                .build());
    }

    @GET(path = "/backend/admin/ldap", permission = "admin:view:ldap")
    String getLdapConfig() throws Exception {
        List<String> allGlowrootRoles = Lists.newArrayList();
        for (RoleConfig roleConfig : configRepository.getRoleConfigs()) {
            allGlowrootRoles.add(roleConfig.name());
        }
        allGlowrootRoles = Ordering.natural().sortedCopy(allGlowrootRoles);
        return mapper.writeValueAsString(ImmutableLdapConfigResponse.builder()
                .config(LdapConfigDto.create(configRepository.getLdapConfig()))
                .allGlowrootRoles(allGlowrootRoles)
                .build());
    }

    @POST(path = "/backend/admin/web", permission = "admin:edit:web")
    Object updateWebConfig(@BindRequest WebConfigDto configDto) throws Exception {
        // this code cannot be reached when httpServer is null
        checkNotNull(httpServer);
        WebConfig priorConfig = configRepository.getWebConfig();
        WebConfig config = configDto.convert();
        try {
            configRepository.updateWebConfig(config, configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return onSuccessfulAccessUpdate(priorConfig, config);
    }

    @POST(path = "/backend/admin/storage", permission = "admin:edit:storage")
    String updateStorageConfig(@BindRequest String content) throws Exception {
        if (fat) {
            FatStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableFatStorageConfigDto.class);
            try {
                configRepository.updateFatStorageConfig(configDto.convert(), configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            repoAdmin.resizeIfNecessary();
        } else {
            ServerStorageConfigDto configDto =
                    mapper.readValue(content, ImmutableServerStorageConfigDto.class);
            try {
                configRepository.updateServerStorageConfig(configDto.convert(),
                        configDto.version());
            } catch (OptimisticLockException e) {
                throw new JsonServiceException(PRECONDITION_FAILED, e);
            }
            repoAdmin.resizeIfNecessary();
        }
        return getStorageConfig();
    }

    @POST(path = "/backend/admin/smtp", permission = "admin:edit:smtp")
    String updateSmtpConfig(@BindRequest SmtpConfigDto configDto) throws Exception {
        try {
            configRepository.updateSmtpConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getSmtpConfig();
    }

    @POST(path = "/backend/admin/ldap", permission = "admin:edit:ldap")
    String updateLdapConfig(@BindRequest LdapConfigDto configDto) throws Exception {
        try {
            configRepository.updateLdapConfig(configDto.convert(configRepository),
                    configDto.version());
        } catch (OptimisticLockException e) {
            throw new JsonServiceException(PRECONDITION_FAILED, e);
        }
        return getLdapConfig();
    }

    @POST(path = "/backend/admin/send-test-email", permission = "admin:edit:smtp")
    String sendTestEmail(@BindRequest SmtpConfigDto configDto) throws IOException {
        String testEmailRecipient = configDto.testEmailRecipient();
        checkNotNull(testEmailRecipient);
        List<String> emailAddresses =
                Splitter.on(',').trimResults().splitToList(testEmailRecipient);
        try {
            AlertingService.sendEmail(emailAddresses, "Test email from Glowroot", "",
                    configDto.convert(configRepository), configRepository, mailService);
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e.getMessage());
        }
        return "{}";
    }

    @POST(path = "/backend/admin/test-ldap-connection", permission = "admin:edit:ldap")
    String testLdapConnection(@BindRequest LdapConfigDto configDto) throws Exception {
        LdapConfig config = configDto.convert(configRepository);
        String authTestUsername = checkNotNull(configDto.authTestUsername());
        String authTestPassword = checkNotNull(configDto.authTestPassword());
        Set<String> ldapGroupDns;
        try {
            ldapGroupDns = LdapAuthentication.authenticateAndGetLdapGroupDns(authTestUsername,
                    authTestPassword, config, configRepository.getSecretKey());
        } catch (AuthenticationException e) {
            logger.debug(e.getMessage(), e);
            return createErrorResponse(e.getMessage());
        }
        Set<String> glowrootRoles = LdapAuthentication.getGlowrootRoles(ldapGroupDns, config);
        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        jg.writeObjectField("ldapGroupDns", ldapGroupDns);
        jg.writeObjectField("glowrootRoles", glowrootRoles);
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    @POST(path = "/backend/admin/delete-all-stored-data", permission = "admin:edit:storage")
    void deleteAllData() throws Exception {
        repoAdmin.deleteAllData();
        liveAggregateRepository.clearInMemoryAggregate();
    }

    @POST(path = "/backend/admin/defrag-data", permission = "admin:edit:storage")
    void defragData() throws Exception {
        repoAdmin.defrag();
    }

    @RequiresNonNull("httpServer")
    private Object onSuccessfulAccessUpdate(WebConfig priorConfig, WebConfig config)
            throws Exception {
        boolean portChangedSucceeded = false;
        boolean portChangedFailed = false;
        if (priorConfig.port() != config.port()) {
            try {
                httpServer.changePort(config.port());
                portChangedSucceeded = true;
            } catch (PortChangeFailedException e) {
                logger.error(e.getMessage(), e);
                portChangedFailed = true;
            }
        }
        String responseText = getWebConfig(portChangedFailed);
        ByteBuf responseContent = Unpooled.copiedBuffer(responseText, Charsets.ISO_8859_1);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, responseContent);
        if (portChangedSucceeded) {
            response.headers().set("Glowroot-Port-Changed", "true");
        }
        return response;
    }

    @RequiresNonNull("httpServer")
    private String getWebConfig(boolean portChangeFailed) throws Exception {
        WebConfig config = configRepository.getWebConfig();
        return mapper.writeValueAsString(ImmutableWebConfigResponse.builder()
                .config(WebConfigDto.create(config))
                .activePort(httpServer.getPort())
                .activeBindAddress(httpServer.getBindAddress())
                .portChangeFailed(portChangeFailed)
                .build());
    }

    private static String createErrorResponse(@Nullable String message) throws IOException {
        StringWriter sw = new StringWriter();
        JsonGenerator jg = mapper.getFactory().createGenerator(sw);
        jg.writeStartObject();
        jg.writeBooleanField("error", true);
        jg.writeStringField("message", message);
        jg.writeEndObject();
        jg.close();
        return sw.toString();
    }

    @Value.Immutable
    interface ChangePassword {
        String currentPassword();
        String newPassword();
    }

    @Value.Immutable
    interface WebConfigResponse {
        WebConfigDto config();
        int activePort();
        String activeBindAddress();
        boolean portChangeFailed();
    }

    @Value.Immutable
    interface SmtpConfigResponse {
        SmtpConfigDto config();
        String localServerName();
    }

    @Value.Immutable
    interface LdapConfigResponse {
        LdapConfigDto config();
        List<String> allGlowrootRoles();
    }

    @Value.Immutable
    abstract static class WebConfigDto {

        abstract int port();
        abstract String bindAddress();
        abstract int sessionTimeoutMinutes();
        abstract String version();

        private WebConfig convert() throws Exception {
            return ImmutableWebConfig.builder()
                    .port(port())
                    .bindAddress(bindAddress())
                    .sessionTimeoutMinutes(sessionTimeoutMinutes())
                    .build();
        }

        private static WebConfigDto create(WebConfig config) {
            return ImmutableWebConfigDto.builder()
                    .port(config.port())
                    .bindAddress(config.bindAddress())
                    .sessionTimeoutMinutes(config.sessionTimeoutMinutes())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class FatStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract int fullQueryTextExpirationHours();
        abstract ImmutableList<Integer> rollupCappedDatabaseSizesMb();
        abstract int traceCappedDatabaseSizeMb();
        abstract String version();

        private FatStorageConfig convert() {
            return ImmutableFatStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .fullQueryTextExpirationHours(fullQueryTextExpirationHours())
                    .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(traceCappedDatabaseSizeMb())
                    .build();
        }

        private static FatStorageConfigDto create(FatStorageConfig config) {
            return ImmutableFatStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .fullQueryTextExpirationHours(config.fullQueryTextExpirationHours())
                    .addAllRollupCappedDatabaseSizesMb(config.rollupCappedDatabaseSizesMb())
                    .traceCappedDatabaseSizeMb(config.traceCappedDatabaseSizeMb())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class ServerStorageConfigDto {

        abstract ImmutableList<Integer> rollupExpirationHours();
        abstract int traceExpirationHours();
        abstract int fullQueryTextExpirationHours();
        abstract String version();

        private ServerStorageConfig convert() {
            return ImmutableServerStorageConfig.builder()
                    .rollupExpirationHours(rollupExpirationHours())
                    .traceExpirationHours(traceExpirationHours())
                    .fullQueryTextExpirationHours(fullQueryTextExpirationHours())
                    .build();
        }

        private static ServerStorageConfigDto create(ServerStorageConfig config) {
            return ImmutableServerStorageConfigDto.builder()
                    .addAllRollupExpirationHours(config.rollupExpirationHours())
                    .traceExpirationHours(config.traceExpirationHours())
                    .fullQueryTextExpirationHours(config.fullQueryTextExpirationHours())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class SmtpConfigDto {

        abstract String host();
        abstract @Nullable Integer port();
        abstract boolean ssl();
        abstract String username();
        abstract boolean passwordExists();
        @Value.Default
        String newPassword() { // only used in request
            return "";
        }
        abstract Map<String, String> additionalProperties();
        abstract String fromEmailAddress();
        abstract String fromDisplayName();
        abstract @Nullable String testEmailRecipient(); // only used in request
        abstract String version();

        private SmtpConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableSmtpConfig.Builder builder = ImmutableSmtpConfig.builder()
                    .host(host())
                    .port(port())
                    .ssl(ssl())
                    .username(username())
                    .putAllAdditionalProperties(additionalProperties())
                    .fromEmailAddress(fromEmailAddress())
                    .fromDisplayName(fromDisplayName());
            if (!passwordExists()) {
                // clear password
                builder.password("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                String newPassword =
                        Encryption.encrypt(newPassword(), configRepository.getSecretKey());
                builder.password(newPassword);
            } else {
                // keep existing password
                builder.password(configRepository.getSmtpConfig().password());
            }
            return builder.build();
        }

        private static SmtpConfigDto create(SmtpConfig config) {
            return ImmutableSmtpConfigDto.builder()
                    .host(config.host())
                    .port(config.port())
                    .ssl(config.ssl())
                    .username(config.username())
                    .passwordExists(!config.password().isEmpty())
                    .putAllAdditionalProperties(config.additionalProperties())
                    .fromEmailAddress(config.fromEmailAddress())
                    .fromDisplayName(config.fromDisplayName())
                    .version(config.version())
                    .build();
        }
    }

    @Value.Immutable
    abstract static class LdapConfigDto {

        abstract String host();
        abstract @Nullable Integer port();
        abstract boolean ssl();
        abstract String username();
        abstract boolean passwordExists();
        @Value.Default
        String newPassword() { // only used in request
            return "";
        }
        abstract String userBaseDn();
        abstract String userSearchFilter();
        abstract String groupBaseDn();
        abstract String groupSearchFilter();
        abstract Map<String, List<String>> roleMappings();
        abstract @Nullable String authTestUsername(); // only used in request
        abstract @Nullable String authTestPassword(); // only used in request
        abstract String version();

        private LdapConfig convert(ConfigRepository configRepository) throws Exception {
            ImmutableLdapConfig.Builder builder = ImmutableLdapConfig.builder()
                    .host(host())
                    .port(port())
                    .ssl(ssl())
                    .username(username())
                    .userBaseDn(userBaseDn())
                    .userSearchFilter(userSearchFilter())
                    .groupBaseDn(groupBaseDn())
                    .groupSearchFilter(groupSearchFilter())
                    .roleMappings(roleMappings());
            if (!passwordExists()) {
                // clear password
                builder.password("");
            } else if (passwordExists() && !newPassword().isEmpty()) {
                // change password
                String newPassword =
                        Encryption.encrypt(newPassword(), configRepository.getSecretKey());
                builder.password(newPassword);
            } else {
                // keep existing password
                builder.password(configRepository.getLdapConfig().password());
            }
            return builder.build();
        }

        private static LdapConfigDto create(LdapConfig config) {
            return ImmutableLdapConfigDto.builder()
                    .host(config.host())
                    .port(config.port())
                    .ssl(config.ssl())
                    .username(config.username())
                    .passwordExists(!config.password().isEmpty())
                    .userBaseDn(config.userBaseDn())
                    .userSearchFilter(config.userSearchFilter())
                    .groupBaseDn(config.groupBaseDn())
                    .groupSearchFilter(config.groupSearchFilter())
                    .roleMappings(config.roleMappings())
                    .version(config.version())
                    .build();
        }
    }
}

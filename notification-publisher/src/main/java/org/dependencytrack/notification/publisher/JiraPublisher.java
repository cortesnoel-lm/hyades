package org.dependencytrack.notification.publisher;

import io.pebbletemplates.pebble.PebbleEngine;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.JsonObject;
import org.dependencytrack.common.SecretDecryptor;
import org.dependencytrack.persistence.model.ConfigProperty;
import org.dependencytrack.persistence.model.ConfigPropertyConstants;
import org.dependencytrack.persistence.repository.ConfigPropertyRepository;
import org.dependencytrack.proto.notification.v1.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.dependencytrack.persistence.model.ConfigPropertyConstants.JIRA_PASSWORD;
import static org.dependencytrack.persistence.model.ConfigPropertyConstants.JIRA_USERNAME;

@ApplicationScoped
@Startup // Force bean creation even though no direct injection points exist
public class JiraPublisher extends AbstractWebhookPublisher implements Publisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(JiraPublisher.class);

    private final PebbleEngine pebbleEngine;
    private final ConfigPropertyRepository configPropertyRepository;
    private final SecretDecryptor secretDecryptor;
    private String jiraProjectKey;
    private String jiraTicketType;

    @Inject
    public JiraPublisher(@Named("pebbleEngineJson") final PebbleEngine pebbleEngine,
                         final ConfigPropertyRepository configPropertyRepository,
                         final SecretDecryptor secretDecryptor) {
        this.pebbleEngine = pebbleEngine;
        this.configPropertyRepository = configPropertyRepository;
        this.secretDecryptor = secretDecryptor;
    }

    @Override
    public String getDestinationUrl(final JsonObject config) {
        final ConfigProperty baseUrlProperty = QuarkusTransaction.joiningExisting()
                .call(() -> configPropertyRepository.findByGroupAndName(
                        ConfigPropertyConstants.JIRA_URL.getGroupName(),
                        ConfigPropertyConstants.JIRA_URL.getPropertyName()
                ));
        if (baseUrlProperty == null) {
            return null;
        }

        final String baseUrl = baseUrlProperty.getPropertyValue();
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + '/') + "rest/api/2/issue";
    }

    @Override
    protected AuthCredentials getAuthCredentials() throws Exception {
        final String jiraUsername = configPropertyRepository.findByGroupAndName(JIRA_USERNAME.getGroupName(), JIRA_USERNAME.getPropertyName()).getPropertyValue();
        final String encryptedPassword = configPropertyRepository.findByGroupAndName(JIRA_PASSWORD.getGroupName(), JIRA_PASSWORD.getPropertyName()).getPropertyValue();
        final String jiraPassword = (encryptedPassword == null) ? null : secretDecryptor.decryptAsString(encryptedPassword);
        return new AuthCredentials(jiraUsername, jiraPassword);
    }

    @Override
    public void inform(final PublishContext ctx, final Notification notification, final JsonObject config) throws Exception {
        if (config == null) {
            LOGGER.warn("No publisher configuration provided; Skipping notification (%s)".formatted(ctx));
            return;
        }

        jiraTicketType = config.getString("jiraTicketType", null);
        if (jiraTicketType == null) {
            LOGGER.warn("No JIRA ticket type configured; Skipping notification (%s)".formatted(ctx));
            return;
        }

        jiraProjectKey = config.getString(CONFIG_DESTINATION, null);
        if (jiraProjectKey == null) {
            LOGGER.warn("No JIRA project key configured; Skipping notification (%s)".formatted(ctx));
            return;
        }

        publish(ctx, getTemplate(config), notification, config, configPropertyRepository);
    }

    @Override
    public PebbleEngine getTemplateEngine() {
        return pebbleEngine;
    }

    @Override
    public void enrichTemplateContext(final Map<String, Object> context, JsonObject config) {
        jiraTicketType = config.getString("jiraTicketType");
        jiraProjectKey = config.getString(CONFIG_DESTINATION);
        context.put("jiraProjectKey", jiraProjectKey);
        context.put("jiraTicketType", jiraTicketType);
    }
}

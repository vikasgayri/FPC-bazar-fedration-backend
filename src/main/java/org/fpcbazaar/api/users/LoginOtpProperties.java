package org.fpcbazaar.api.users;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Second sign-in step: after the password is accepted, a 6-digit code is emailed and must be entered.
 * {@code enabled=false} (LOGIN_OTP=false) is only for local testing without email.
 */
@ConfigurationProperties("app.login-otp")
public record LoginOtpProperties(
    @DefaultValue("true") boolean enabled,
    /** How long one code works. */
    @DefaultValue("10m") Duration ttl,
    /** Wrong codes allowed for one sign-in before the user must enter the password again. */
    @DefaultValue("5") int maxAttempts,
    /** Wait before "send the code again" works. */
    @DefaultValue("60s") Duration resendAfter,
    /** Emails per sign-in (first one included). */
    @DefaultValue("4") int maxSends,
    /** New sign-in codes per login within {@code issueWindow}; stops a stolen password from flooding the inbox. */
    @DefaultValue("6") int maxIssuesPerWindow,
    @DefaultValue("15m") Duration issueWindow) {}

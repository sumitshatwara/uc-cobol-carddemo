package com.carddemo.online.store;

/** USRSEC record, copybook CSUSR01Y (SEC-USER-DATA). Table carddemo_online.user_security. */
public record UserSecurity(String userId, String firstName, String lastName, String password, String userType) {}

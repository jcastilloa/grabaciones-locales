package org.jcastillo.grabaciones;

interface ILogCallback {
    void onLogEvent(String level, String tag, String message, String throwableStackTrace);
}
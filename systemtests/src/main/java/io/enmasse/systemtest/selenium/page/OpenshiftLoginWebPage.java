/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.selenium.page;

import io.enmasse.systemtest.annotations.OpenShiftVersion;
import io.enmasse.systemtest.annotations.OpenShiftVersion.Openshift4MinorVersion;
import io.enmasse.systemtest.logs.CustomLogger;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.selenium.SeleniumProvider;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;

import java.time.Duration;

public class OpenshiftLoginWebPage implements IWebPage {

    private static Logger log = CustomLogger.getLogger();

    SeleniumProvider selenium;

    public OpenshiftLoginWebPage(SeleniumProvider selenium) {
        this.selenium = selenium;
    }

    private WebElement getUsernameTextInput() {
        return selenium.getDriver().findElement(By.id("inputUsername"));
    }

    private WebElement getPasswordTextInput() {
        return selenium.getDriver().findElement(By.id("inputPassword"));
    }

    private WebElement getLoginButton() {
        if (Kubernetes.getInstance().getOcpVersion() == OpenShiftVersion.OCP4) {
            return selenium.getDriver().findElement(By.xpath("//button[contains(text(), 'Log')]"));
        } else {
            return selenium.getDriver().findElement(By.className("btn-lg"));
        }
    }

    private WebElement getAlertContainer() {
        return selenium.getDriver().findElement(By.className("alert"));
    }

    private WebElement getHtpasswdButton() {
        return selenium.getDriver().findElement(By.partialLinkText("htpasswd"));
    }

    public String getAlertMessage() {
        if (Kubernetes.getInstance().getOcpVersion() == OpenShiftVersion.OCP4) {
            var k8sVersion = Kubernetes.getInstance().getKubernetesVersion();
            if (Openshift4MinorVersion.fromK8sVersion(k8sVersion) == Openshift4MinorVersion.OCP4_AFTER_4_4) {
                return selenium.getDriver().findElement(By.className("error-placeholder")).getText();
            } else {
                return selenium.getDriver().findElement(By.className("alert-danger")).getText();
            }
        } else {
            return getAlertContainer().findElement(By.className("kc-feedback-text")).getText();
        }
    }

    private boolean checkAlert() {
        try {
            getAlertMessage();
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    public boolean login(String username, String password) throws Exception {
        checkReachableWebPage();
        log.info("Try to login with credentials {} : {}", username, password);
        selenium.fillInputItem(getUsernameTextInput(), username);
        selenium.fillInputItem(getPasswordTextInput(), password);
        selenium.clickOnItem(getLoginButton(), "Log in");
        return checkAlert();
    }

    @Override
    public void checkReachableWebPage() {
        if (Kubernetes.getInstance().getOcpVersion() == OpenShiftVersion.OCP4) {
            selenium.getDriverWait().withTimeout(Duration.ofSeconds(30)).until(ExpectedConditions.urlContains("oauth/authorize"));
        } else {
            selenium.getDriverWait().withTimeout(Duration.ofSeconds(30)).until(ExpectedConditions.presenceOfElementLocated(By.id("inputPassword")));
        }
        selenium.getAngularDriver().waitForAngularRequestsToFinish();
        selenium.takeScreenShot();
        if (Kubernetes.getInstance().getOcpVersion() == OpenShiftVersion.OCP4) {
            selenium.clickOnItem(getHtpasswdButton(), "Htpasswd log in page");
            selenium.takeScreenShot();
        }
    }
}

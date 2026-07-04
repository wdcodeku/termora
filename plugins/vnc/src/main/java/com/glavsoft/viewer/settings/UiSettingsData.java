// Copyright (C) 2010 - 2014 GlavSoft LLC.
// All rights reserved.
//
// -----------------------------------------------------------------------
// This file is part of the TightVNC software.  Please visit our Web site:
//
//                       http://www.tightvnc.com/
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
// -----------------------------------------------------------------------
//
package com.glavsoft.viewer.settings;

import java.io.Serializable;

/**
 * @author dime at tightvnc.com
 */
public class UiSettingsData implements Serializable {
    private static final long serialVersionUID = 1L;
    private double scalePercent;
    private LocalMouseCursorShape mouseCursorShape;
    private boolean fullScreen;
    private String resolutionOptimization; // "auto", "native", "fit", "stretch"
    private boolean autoReconnect;
    private boolean saveCredentials;


    public UiSettingsData() {
        scalePercent = 100;
        mouseCursorShape = LocalMouseCursorShape.DOT;
        fullScreen = false;
        resolutionOptimization = "auto";
        autoReconnect = false;
        saveCredentials = false;
    }

    public UiSettingsData(double scalePercent, LocalMouseCursorShape mouseCursorShape, boolean fullScreen) {
        this.scalePercent = scalePercent;
        this.mouseCursorShape = mouseCursorShape;
        this.fullScreen = fullScreen;
        this.resolutionOptimization = "auto";
        this.autoReconnect = false;
        this.saveCredentials = false;
    }

    public UiSettingsData(double scalePercent, LocalMouseCursorShape mouseCursorShape, boolean fullScreen,
                         String resolutionOptimization, boolean autoReconnect, boolean saveCredentials) {
        this.scalePercent = scalePercent;
        this.mouseCursorShape = mouseCursorShape;
        this.fullScreen = fullScreen;
        this.resolutionOptimization = resolutionOptimization;
        this.autoReconnect = autoReconnect;
        this.saveCredentials = saveCredentials;
    }

    public UiSettingsData(UiSettingsData other) {
        this(other.getScalePercent(), other.getMouseCursorShape(), other.isFullScreen(),
             other.getResolutionOptimization(), other.isAutoReconnect(), other.isSaveCredentials());
    }

    public double getScalePercent() {
        return scalePercent;
    }

    public boolean setScalePercent(double scalePercent) {
        if (this.scalePercent != scalePercent) {
            this.scalePercent = scalePercent;
            return true;
        }
        return false;
    }


    public LocalMouseCursorShape getMouseCursorShape() {
        return mouseCursorShape;
    }

    public boolean setMouseCursorShape(LocalMouseCursorShape mouseCursorShape) {
        if (this.mouseCursorShape != mouseCursorShape && mouseCursorShape != null) {
            this.mouseCursorShape = mouseCursorShape;
            return true;
        }
        return false;
    }

    public boolean isFullScreen() {
        return fullScreen;
    }

    public boolean setFullScreen(boolean fullScreen) {
        if (this.fullScreen != fullScreen) {
            this.fullScreen = fullScreen;
            return true;
        }
        return false;
    }
    
    public String getResolutionOptimization() {
        return resolutionOptimization;
    }
    
    public boolean setResolutionOptimization(String resolutionOptimization) {
        if (this.resolutionOptimization == null || !this.resolutionOptimization.equals(resolutionOptimization)) {
            this.resolutionOptimization = resolutionOptimization;
            return true;
        }
        return false;
    }
    
    public boolean isAutoReconnect() {
        return autoReconnect;
    }
    
    public boolean setAutoReconnect(boolean autoReconnect) {
        if (this.autoReconnect != autoReconnect) {
            this.autoReconnect = autoReconnect;
            return true;
        }
        return false;
    }
    
    public boolean isSaveCredentials() {
        return saveCredentials;
    }
    
    public boolean setSaveCredentials(boolean saveCredentials) {
        if (this.saveCredentials != saveCredentials) {
            this.saveCredentials = saveCredentials;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "UiSettingsData{" +
                "scalePercent=" + scalePercent +
                ", mouseCursorShape=" + mouseCursorShape +
                ", fullScreen=" + fullScreen +
                ", resolutionOptimization='" + resolutionOptimization + '\'' +
                ", autoReconnect=" + autoReconnect +
                ", saveCredentials=" + saveCredentials +
                '}';
    }
}
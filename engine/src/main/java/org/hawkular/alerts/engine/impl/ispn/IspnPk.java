package org.hawkular.alerts.engine.impl.ispn;

import org.hawkular.alerts.api.model.action.ActionDefinition;
import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.event.Alert;
import org.hawkular.alerts.api.model.trigger.Trigger;

/**
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
public class IspnPk {

    public static String pk(String actionPlugin) {
        if (actionPlugin == null) {
            return null;
        }
        return new StringBuilder("ActionPlugin-").append(actionPlugin).toString();
    }

    public static String pk(ActionDefinition actionDefinition) {
        if (actionDefinition == null) {
            return null;
        }
        return new StringBuilder("ActionDefinition-")
                .append(actionDefinition.getTenantId())
                .append("-")
                .append(actionDefinition.getActionPlugin())
                .append("-")
                .append(actionDefinition.getActionId())
                .toString();
    }

    public static String pk(String tenantId, String actionPlugin, String actionId) {
        if (tenantId == null || actionPlugin == null || actionId == null) {
            return null;
        }
        return new StringBuilder("ActionDefinition-")
                .append(tenantId)
                .append("-")
                .append(actionPlugin)
                .append("-")
                .append(actionId)
                .toString();
    }

    public static String pk(Condition condition) {
        if (condition == null) {
            return null;
        }
        return new StringBuilder("Condition-")
                .append(condition.getConditionId())
                .toString();
    }

    public static String pk(Trigger trigger) {
        if (trigger == null) {
            return null;
        }
        return new StringBuilder("Trigger-")
                .append(trigger.getTenantId())
                .append("-")
                .append(trigger.getId())
                .toString();
    }

    public static String pk(String tenantId, String triggerId) {
        if (tenantId == null || triggerId == null) {
            return null;
        }
        return new StringBuilder("Trigger-")
                .append(tenantId)
                .append("-")
                .append(triggerId)
                .toString();
    }

    public static String pk(Alert alert) {
        if (alert == null) {
            return null;
        }
        return new StringBuilder("Alert-")
                .append(alert.getTenantId())
                .append("-")
                .append(alert.getAlertId())
                .toString();
    }

}
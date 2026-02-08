package net.bigtangle.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController()

public class LifecycleController extends LifecycleBasisController {
    private static final Logger log = LoggerFactory.getLogger(LifecycleBasisController.class);
    @Autowired
    protected LifecycleController(ApplicationContext appContext) {
        super(appContext);
    }

    @GetMapping(value = "/lifecycle/afterStart", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LifecycleStatus> afterStart() {
        log.debug("afterStartContainer");
        return buildResponse(afterStartContainer());
    }

    @GetMapping(value = "/lifecycle/liveness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LifecycleStatus> checkLivenessStatus() {
        log.debug("checkLifenessStatus");
        return buildResponse(checkStatus());
    }

    @GetMapping(value = "/readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LifecycleStatus> readynessStatus() {
        log.debug("checkReadinessStatus");
        return buildResponse(checkStatus());
    }

    @GetMapping(value = "/liveness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LifecycleStatus> healthStatus() {
        log.debug("checkHealthStatus");
        return buildResponse(checkStatus());
    }

    protected ResponseEntity<LifecycleStatus> buildResponse(LifecycleStatus status) {
        if (status.getStatus().equals(LifecycleStatus.STATUS_OK)) {
            return new ResponseEntity<>(status, HttpStatus.OK);
        }
        return new ResponseEntity<>(status, HttpStatus.SERVICE_UNAVAILABLE);
    }

}

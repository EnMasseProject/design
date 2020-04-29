/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import React from "react";
import {
  isMessagingProjectValid,
  isIoTProjectValid
} from "modules/msg-and-iot/dailogs/utils";
import { IIoTProjectInput } from "modules/msg-and-iot/dailogs/components";
import { IoTReview } from "../IoTReview";

const IoTProjectReview = (projectDetail?: IIoTProjectInput) => {
  const isReviewEnabled = () => {
    isIoTProjectValid(projectDetail);
  };
  const isFinishEnabled = () => {
    isIoTProjectValid(projectDetail);
  };
  return {
    name: "Review",
    isDisabled: true,
    component: (
      <IoTReview
        name={projectDetail && projectDetail.iotProjectName}
        namespace={(projectDetail && projectDetail.namespace) || ""}
        isEnabled={(projectDetail && projectDetail.isEnabled) || false}
      />
    ),
    enableNext: isFinishEnabled(),
    canJumpTo: isReviewEnabled(),
    nextButtonText: "Finish"
  };
};

export { IoTProjectReview };
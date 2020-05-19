/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import React from "react";
import { MemoryRouter } from "react-router";
import {
  ConnectionList,
  IConnection
} from "modules/connection/components/ConnectionList/ConnectionList";
import { EmptyConnection } from "modules/connection/components/EmptyConnection/EmptyConnection";
import { action } from "@storybook/addon-actions";

export default {
  title: "Connection"
};

const rows: IConnection[] = [
  {
    hostname: "foo",
    containerId: "123",
    protocol: "AMQP",
    encrypted: true,
    messageIn: 123,
    messageOut: 123,
    senders: 123,
    receivers: 123,
    status: "running",
    name: "juno2",
    creationTimestamp: "2020-01-20T11:44:28.607Z"
  },
  {
    hostname: "foo",
    containerId: "123",
    protocol: "AMQP",
    encrypted: true,
    messageIn: 123,
    messageOut: 123,
    senders: 123,
    receivers: 123,
    status: "running",
    name: "juno3",
    creationTimestamp: "2020-01-20T11:44:28.607Z"
  },
  {
    hostname: "foo",
    containerId: "123",
    protocol: "AMQP",
    encrypted: true,
    messageIn: 123,
    messageOut: 123,
    senders: 123,
    receivers: 123,
    status: "running",
    name: "juno4",
    creationTimestamp: "2020-01-20T11:44:28.607Z"
  }
];

export const connectionList = () => (
  <MemoryRouter>
    <ConnectionList
      rows={rows}
      onCloseConnection={action("onCloseConnection")}
      onSelectAllConnection={action("select all connection")}
      onSelectConnection={action("select connection")}
    />
  </MemoryRouter>
);

export const emptyConnectionList = () => (
  <MemoryRouter>
    <EmptyConnection />
  </MemoryRouter>
);

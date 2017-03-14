{
  local port = {
    "name": "mqtt",
    "port": 1883,
    "protocol": "TCP",
    "targetPort": 1883
  },
  local securePort = {
    "name": "secure-mqtt",
    "port": 8883,
    "protocol": "TCP",
    "targetPort": 8883
  },
  generate(secure, tenant)::
    {
      "apiVersion": "v1",
      "kind": "Service",
      "metadata": {
        "labels": {
          "app": "enmasse",
          "tenant": tenant
        },
        "name": "mqtt"
      },
      "spec": {
        "ports": if secure then [port, securePort] else [port],
        "selector": {
          "name": "mqtt-gateway",
          "tenant": tenant
        }
      }
    }
}

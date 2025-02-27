#!/bin/bash
REGISTRY=${1}

if [ "${REGISTRY}" == "" ]; then 
    echo "Usage: ${0} registry.example.com"
    exit 1
fi

oc patch --type=merge --patch="{ \"spec\": { \"registrySources\": { \"insecureRegistries\": [ \"${REGISTRY}\" ] } } }" image.config.openshift.io/cluster

IP=$(crc ip)
PORT=22
if [[ "${IP}" == "127.0.0.1" ]]; then
   PORT=2222
fi

ID_FILE=~/.crc/machines/crc/id_ecdsa
if [[ ! -f "${ID_FILE}" ]]; then
    ID_FILE=~/.crc/machines/crc/id_rsa
fi


ssh -p ${PORT} -i "${ID_FILE}" -o StrictHostKeyChecking=no core@${IP} sudo cat /etc/containers/registries.conf > /tmp/registries.conf


cat <<EOF >> /tmp/registries.conf
[[registry]]
  location = "${REGISTRY}"
  insecure = true
  blocked = false
  mirror-by-digest-only = false
  prefix = ""
EOF

echo "Replaced contents: "
cat /tmp/registries.conf
echo "Press ENTER to copy to CRC VM"
read

scp -P ${PORT} -i "${ID_FILE}" -o StrictHostKeyChecking=no /tmp/registries.conf core@${IP}:/tmp/registries.conf
ssh -p ${PORT} -i "${ID_FILE}" -o StrictHostKeyChecking=no core@${IP} sudo cp /tmp/registries.conf /etc/containers/registries.conf
echo "Restarting crio/kublet"
ssh -p ${PORT} -i "${ID_FILE}" -o StrictHostKeyChecking=no core@${IP} sudo systemctl restart crio
ssh -p ${PORT} -i "${ID_FILE}" -o StrictHostKeyChecking=no core@${IP} sudo systemctl restart kubelet

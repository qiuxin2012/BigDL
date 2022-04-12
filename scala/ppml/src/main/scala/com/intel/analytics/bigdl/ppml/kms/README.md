# 1. Deploy EHSM KMS on Kubernetes

![Deploy eHSM KMS on Kubernetes](https://user-images.githubusercontent.com/60865256/160524763-59ba22d5-dc93-4755-a993-a488cf48a8f9.png)


## Prerequisites

- Ensure you already have a running kubenetes cluster environment, if not, please follow [k8s-setup-guide](https://github.com/intel/ehsm/blob/main/docs/k8s-setup-guide.md) to setup the K8S cluster.
- Ensure you already have a NFS server, if not, please follow [nfs-setup-guide](https://github.com/intel/ehsm/blob/main/docs/nfs-setup-guide.md) to setup a nfs server.



## Deployment

First, download eHSM and couchdb images needed:

```bash
docker pull intelccc/ehsm_kms:0.2.0 #Please make sure the version number is the latest, 0.2.0 when writing
docker pull couchdb:3.2
```

Copy the following and save to a `ehsm-kms.yaml`:

```yaml
# ehsm-kms Secret
apiVersion: v1
kind: Secret
metadata:
    name: ehsm-secret
    namespace: ehsm-kms
type: Opaque
data:
    couch_root_username: YWRtaW4=
    couch_root_password: cGFzc3dvcmQ=

---
# ehsm-kms ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: ehsm-configmap
  namespace: ehsm-kms
data:
  database_url: "couchdb-0.couchdb"
  database_port: "5984"
  database_name: "ehsm_kms_db"
  # you need adjust https://1.2.3.4:8081 to your pccs_url.
  pccs_url: "https://10.112.240.166:8081"

---
# ehsm-kms PersistentVolume for CouchDB
apiVersion: v1
kind: PersistentVolume 
metadata:
  name: ehsm-pv-nfs
spec:
  capacity:
    storage: 10Gi 
  accessModes:
    - ReadWriteOnce 
  persistentVolumeReclaimPolicy: Retain 
  storageClassName: nfs
  nfs:
    # This path is the folder path on your NFS server
    path: /nfs_ehsm_db
    # you need adjust 1.2.3.4 to your NFS IP.
    server: 1.2.3.4

---
# ehsm-kms CouchDB
apiVersion: v1
kind: Service
metadata:
  name: couchdb
  namespace: ehsm-kms
  labels:
    app: couchdb
spec:
  ports:
    - name: couchdb
      port: 5984
      targetPort: 5984
  selector:
    app: couchdb
  clusterIP: None 
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: couchdb
  namespace: ehsm-kms
spec:
  selector:
    matchLabels:
      app: couchdb
  serviceName: "couchdb"
  replicas: 1
  template:
    metadata:
      labels:
        app: couchdb
    spec:
      containers:
      - name: couchdb
        image: couchdb:3.2
        imagePullPolicy: IfNotPresent
        readinessProbe:
          httpGet:
            port: couchdb-port
            path: /
          initialDelaySeconds: 5
          periodSeconds: 10
        ports: 
        - containerPort: 5984
          name: couchdb-port
        volumeMounts:
        - name: couch-persistent-storage
          mountPath: /opt/couchdb/data
        env:
          - name: COUCHDB_USER
            valueFrom:
              secretKeyRef:
                name: ehsm-secret
                key: couch_root_username
          - name: COUCHDB_PASSWORD
            valueFrom:
              secretKeyRef:
                name: ehsm-secret
                key: couch_root_password

  volumeClaimTemplates:
  - metadata:
      name: couch-persistent-storage
    spec:
      accessModes: [ "ReadWriteOnce" ]
      storageClassName: "nfs"
      resources:
        requests:
          storage: 10Gi

---
# ehsm-kms service
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ehsm-kms-deployment
  namespace: ehsm-kms
spec:
  replicas: 3
  selector:
    matchLabels:
      app: ehsm-kms
  template:
    metadata:
      labels:
        app: ehsm-kms
    spec:
      volumes:
      - name: dev-enclave
        hostPath:
          path: /dev/sgx/enclave
      - name: dev-provision
        hostPath:
          path: /dev/sgx/provision
      - name: dev-aesmd
        hostPath:
          path: /var/run/aesmd
      - name: dev-dkeyprovision
        hostPath:
          path: /var/run/ehsm
      containers:
      - name: ehsm-kms
        # You need to tag the ehsm-kms container image with this name on each worker node or change it to point to a docker hub to get the container image.
        image: intel/ehsm_kms_service:latest
        imagePullPolicy: IfNotPresent
        readinessProbe:
          exec:
            command:
            - sh
            - -c
            - "curl http://${EHSM_CONFIG_COUCHDB_SERVER}:${EHSM_CONFIG_COUCHDB_PORT}/"
          initialDelaySeconds: 10
          periodSeconds: 10
        securityContext:
          privileged: true
        volumeMounts:
        - mountPath: /dev/sgx/enclave
          name: dev-enclave
        - mountPath: /dev/sgx/provision
          name: dev-provision
        - mountPath: /var/run/aesmd
          name: dev-aesmd
        - mountPath: /var/run/ehsm
          name: dev-dkeyprovision
        env:
        - name: PCCS_URL 
          valueFrom:
            configMapKeyRef:
              name: ehsm-configmap
              key: pccs_url
        - name: EHSM_CONFIG_COUCHDB_USERNAME
          valueFrom:
            secretKeyRef:
              name: ehsm-secret
              key: couch_root_username
        - name: EHSM_CONFIG_COUCHDB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: ehsm-secret
              key: couch_root_password
        - name: EHSM_CONFIG_COUCHDB_SERVER
          valueFrom:
            configMapKeyRef:
              name: ehsm-configmap
              key: database_url
        - name: EHSM_CONFIG_COUCHDB_PORT
          valueFrom:
            configMapKeyRef:
              name: ehsm-configmap
              key: database_port
        - name: EHSM_CONFIG_COUCHDB_DB
          valueFrom:
            configMapKeyRef:
              name: ehsm-configmap
              key: database_name
        ports:
        - name: ehsm-kms
          containerPort: 9000
---
apiVersion: v1
kind: Service
metadata:
  name: ehsm-kms-service
  namespace: ehsm-kms
spec:
  type: LoadBalancer
  selector:
    app: ehsm-kms
  ports:
    - name: ehsm-kms
      protocol: TCP
      # This port is ehsm_kms_service access port,you can change it to what you want.
      port: 9000
      targetPort: 9000
      nodePort: 30000
  sessionAffinity: ClientIP
  externalIPs:
  # This IP is ehsm_kms_service access IP, You need to adjust 1.2.3.4 to the service IP you want to expose
  - 1.2.3.4
```

Modify the following parameters in the yaml file:

```yaml
......
pccs_url: "https://10.112.240.166:8081"    --> <your_pccs_url>
......
nfs:
    path: /nfs_ehsm_db                     --> <your_nfs_folder>
    server: 1.2.3.4                        --> <your_nfs_ipaddr>
......
containers:
  - name: ehsm-kms
    image: intel/ehsm_kms_service:latest   --> <tag your ehsm-kms container image as this name on the worker node or modify it to point your private docker hub, e.g. intelccc/ehsm-kms:0.1.0>
    imagePullPolicy: IfNotPresent
......
externalIPs:
- 1.2.3.4                                --> <your_kms_external_ipaddr> # This should be a non-used address
```

Create namespace and apply the yaml file on your kubernetes cluster:

```bash
# Create ehsm-kms namespace
$ kubectl create namespace ehsm-kms

# apply the yaml file with ehsm-kms namespace
$ kubectl apply -f ehsm-kms.yaml -n ehsm-kms
```

Check as below:

```bash
$ kubectl get all -n ehsm-kms
NAME                                      READY   STATUS    RESTARTS       AGE
pod/couchdb-0                             1/1     Running   1 (36h ago)    4d15h
pod/ehsm-kms-deployment-c5d68cd85-524vr   1/1     Running   1 (3d7h ago)   4d15h
pod/ehsm-kms-deployment-c5d68cd85-7bkmk   1/1     Running   1 (3d7h ago)   4d15h
pod/ehsm-kms-deployment-c5d68cd85-xh5s4   1/1     Running   1 (3d7h ago)   4d15h

NAME                       TYPE           CLUSTER-IP       EXTERNAL-IP     PORT(S)          AGE
service/couchdb            ClusterIP      None             <none>          5984/TCP         4d15h
service/ehsm-kms-service   LoadBalancer   10.106.117.148   192.168.0.113   9000:30000/TCP   4d15h

$ curl http://<KMS_SERVER_ETERNAL_IP>:9000/ehsm/?Action=GetVersion
{"code":200,"message":"success!","result":{"git_sha":"ab60af6","version":"0.2.0"}}

```
![eHSM KMS components](https://user-images.githubusercontent.com/60865256/160728446-c8072388-b442-4e24-ba4e-28c6249112c6.png)



## Problem and Solution:

1. When you check ehsm-kms status, if ehsm-kms-deployment pods keep ***CrashLoopBack***, please make sure that you are using the latest eHSM image rather than the older ones.



# 2. Enroll through ehsm-kms_enroll_app

![KMS Key Management](https://user-images.githubusercontent.com/60865256/160524707-4b9576f3-f239-40a9-a228-9c7fec2d10f5.png)

Since only the user with valid APPID and APIKey could request the public cryptographic restful APIs, eHSM-KMS provides a new Enroll APP which is used to retrieve the APPID and APIKey from the eHSM-core enclave via the remote secure channel (based on the SGX remote attestation).

First, clone the eHSM project:

```bash
git clone https://github.com/intel/ehsm.git
```

Compile and get the executable ehsm-kms_enroll_app file:

```bash
sudo apt update

sudo apt install vim autoconf automake build-essential cmake curl debhelper git libcurl4-openssl-dev libprotobuf-dev libssl-dev libtool lsb-release ocaml ocamlbuild protobuf-compiler wget libcurl4 libssl1.1 make g++ fakeroot libelf-dev libncurses-dev flex bison libfdt-dev libncursesw5-dev pkg-config libgtk-3-dev libspice-server-dev libssh-dev python3 python3-pip  reprepro unzip libjsoncpp-dev uuid-dev

cd ehsm
make
cd out/ehsm-kms_enroll_app
ls ehsm-kms_enroll_app
```

Then, you will find a new target file `ehsm-kms_enroll_app` generated.

Now, you can enroll your app through command below, and you will receive a appid-appkey pair from the server:

```bash
./ehsm-kms_enroll_app http://<your_kms_external_ipaddr>:9000/ehsm/


INFO [main.cpp(45) -> main]: ehsm-kms enroll app start.
INFO [main.cpp(69) -> main]: First handle: send msg0 and get msg1.
INFO [main.cpp(82) -> main]: First handle success.
INFO [main.cpp(84) -> main]: Second handle: send msg2 and get msg3.
INFO [main.cpp(101) -> main]: Second handle success.
INFO [main.cpp(103) -> main]: Third handle: send att_result_msg and get ciphertext of the APP ID and API Key.
appid: b6b6ad56-7741-4d37-9313-3c16754a4f63
apikey: TKLJ9ZqL1gusW7FnGBGh9apk5iJZFVkB
INFO [main.cpp(138) -> main]: decrypt APP ID and API Key success.
INFO [main.cpp(139) -> main]: Third handle success.
INFO [main.cpp(142) -> main]: ehsm-kms enroll app end.
```

# 3. Start EHSMKeyManagementService with LocalCryptoExample

### [LocalCryptoExample](https://github.com/analytics-zoo/ppml-e2e-examples/blob/main/spark-encrypt-io/src/main/scala/com/intel/analytics/bigdl/ppml/examples/LocalCryptoExample.scala)

```bash
java -cp target/spark-encrypt-io-0.2-SNAPSHOT-jar-with-dependencies.jar \
  com.intel.analytics.bigdl.ppml.examples.LocalCryptoExample \
  --inputPath /your/single/data/file/to/encrypt/and/decrypt \
  --primaryKeyPath /the/path/you/want/to/put/encrypted/primary/key/at \
  --dataKeyPath /the/path/you/want/to/put/encrypted/data/key/at \
  --kmsServerIP /the/kms/external/ip/prementioned \
  --kmsServerPort 9000 \
  --ehsmAPPID /the/appid/obtained/through/enroll \
  --ehsmAPPKEY /the/appkey/obtained/through/enroll \
  --kmsType EHSMKeyManagementService
```

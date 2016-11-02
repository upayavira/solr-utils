package com.odoko.solr.utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;

import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.common.util.NamedList;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.KeeperState;

/**
 * Utility class that provides the ability to upload configurations and to create 
 * collections when operating in Cloud mode (when you only know the Zookeeper
 * address, and not the URL of any Solr node).
 * 
 * It is all configured with environment variables (in keeping with Docker 
 * conventions), the ACTION variable defining the behaviour.
 * 
 * All actions require ZOOKEEPER to be set to the name:port of the Zookeeper instance
 * or instances (e.g. zk01:2181,zk02:2181,zk03:2181/solr for a namespaced example).
 * 
 * ACTION=upload
 *   uploads Solr configuration files to Zookeeper
 *   CONFIG_NAME: the name Solr will use to refer to these configuration files
 *   CONFIG_DIR:  the path, relative to where this app is running, of the directory
 *                containing the configuration files
 * ACTION=create
 *   Creates a collection within Solr
 *   COLLECTION:  the name of the collection to be created
 *   CONFIG_NAME: the name of the configset to be used for this collection (likely
 *                the same name as used for the 'upload' action above.
 * 
 * @author upayavira
 *
 */
public class Main {
  
  private String zookeeperHost;
  private String zookeeperPort;
  private String chroot;
  private String zookeeper;
  private CloudSolrClient solr;
  private boolean wait;
  
  public static void main(String[] args) throws Exception {
    Main main = new Main();
    main.go();
  }
  
  private void go() throws Exception {
    String cmd = System.getenv("ACTION");
    wait = getenv("WAIT", "true").equals("true");
    debug("Waiting for dependent services: %s", wait ? "true" : "false");
    zookeeperHost = System.getenv("ZOOKEEPER");
    zookeeperPort = getenv("ZOOKEEPER_PORT", "2181");
    chroot = getenv("CHROOT", "/solr");
    if (zookeeperHost == null) {
      throw new Exception("Please specify the ZOOKEEPER environment variable");
    } else {
      zookeeper = resolveZookeeperString(zookeeperHost, zookeeperPort, chroot);
    }
    
    debug("Received ZOOKEEPER=%s, ZOOKEEPER_PORT=%s, CHROOT=%s, Using ZOOKEEPER=%s", zookeeperHost, zookeeperPort, chroot, zookeeper);
    
    if (wait) waitForQuorum(zookeeperHost, zookeeperPort);

    solr = new CloudSolrClient(zookeeper);

    if ("upload".equals(cmd)) {
      
      String configName = System.getenv("CONFIG_NAME");
      if (configName==null) throw new Exception("Please provide a CONFIG_NAME envvar");
      
      String configDir = System.getenv("CONFIG_DIR");
      if (configDir==null) configDir = "configs/" + configName;
      
      upload(configName, configDir);
    
    } else if ("create".equals(cmd)) {
      String collection = System.getenv("COLLECTION");
      if (collection==null) throw new Exception("Please provide a COLLECTION envvar");
      
      String configName = System.getenv("CONFIG_NAME");
      if (configName==null) throw new Exception("Please provide a CONFIG_NAME envvar");
            
      createCollection(collection, configName);
    } else if ("create-alias".equals(cmd)) {
      String aliasName = System.getenv("ALIAS");
      String collection = System.getenv("COLLECTION");
      if (collection==null) throw new Exception("Please provide a COLLECTION envvar");
      
      createAlias(collection, aliasName);
    } else if ("add-replica".equals(cmd)) {
      String collection = System.getenv("COLLECTION");
      String shard = System.getenv("SHARD");
      String node = System.getenv("NODENAME");
      
      if (shard==null) {
        shard = "shard1";
      }
      addReplica(collection, shard, node);
      
    } else if ("delete-replica".equals(cmd)) {
      String collection = System.getenv("COLLECTION");
      String shard = System.getenv("SHARD");
      String replica = System.getenv("REPLICA");
      if (shard == null) {
        shard = "shard1";
      }
      deleteReplica(collection, shard, replica);
    } else if ("create-chroot".equals(cmd)) {
      if (System.getenv("CHROOT")==null) {
        chroot = "/solr";
      }
      addChroot();
    } else if ("upload-solrxml".equals(cmd)) {
      String solrXmlSourcePath = System.getenv("SOLR_XML");
      if (solrXmlSourcePath == null) {
        solrXmlSourcePath = "server/solr/solr.xml";
      }
      uploadSolrXml(solrXmlSourcePath);
    } else if ("resolve".equals(cmd)) {
      waitForPath("/solr.xml");
      System.out.println(zookeeper);
    } else {
      System.out.printf("Unknown command: %s\n", cmd);
    }
    System.out.println("");
    solr.close();
  }

  private List<String> getZookeeperHosts(String zookeeper) throws InterruptedException, NamingException {
    List<String> zkHosts = new ArrayList<String>();
    if (zookeeper.contains(",")) {
      String[] hosts = StringUtils.split(zookeeper, ",");
      for (String host : hosts) {
        zkHosts.add(host);
      }
    } else {
      Hashtable<String,String> env = new Hashtable<String,String>();
      env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
      InitialDirContext context = new InitialDirContext(env);
      Attribute records = null;
      try {
        Attributes lookup = context.getAttributes(zookeeper, new String[] { "A" });
        records = lookup.get("A");
      } catch (NameNotFoundException e) {
        records = null;
      }
      if (records == null || records.size()==1) {
        zkHosts.add(zookeeper);
      } else {
          for (int i = 0; i < records.size(); i++) {
            String zkString = ((String)records.get(i));
            zkHosts.add(zkString);
        }
      }
    }
    return zkHosts;
  }

  private String getZooKeeperStatus(String zkHost, String zkPort) throws NumberFormatException {
    Socket s=null;
    try {
      debug("Connecting to %s:%s", zkHost, Integer.parseInt(zkPort));
      s = new Socket(zkHost, Integer.parseInt(zkPort));
      s.setSoTimeout(10000);
      BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream()));
      bw.write("srvr\n");
      bw.flush();
      BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("Mode:")) {
          String mode = line.substring("Mode: ".length());
          s.close();
          return mode;
        }
      }
    } catch (IOException e) {
      debug("Caught exception " + e.getMessage());
      return "missing";
    }
    return "missing";
  }
  
  private void waitForQuorum(String zookeeper, String zkPort) throws InterruptedException, NamingException {
    List<String> zkHosts = getZookeeperHosts(zookeeper);
    boolean isSingle = zkHosts.size() == 1;
    while (true) {
      if (isSingle) {
        if (getZooKeeperStatus(zkHosts.get(0), zkPort).equals("standalone")) {
          return;
        }
      } else {
        int activeCount = 0;
        for (String zkHost : zkHosts) {
          String status = getZooKeeperStatus(zkHost, zkPort);
          if (status.equals("leader") ||
              status.equals("observer") ||
              status.equals("follower")) {
            activeCount++;
          }
        }
        if (activeCount == zkHosts.size()) {
          return;
        } else {
          debug("%s out of %s ZooKeeper hosts active. Waiting", activeCount, zkHosts.size());
          Thread.sleep(5000);
        }
      }
    }
  }
    
  private void waitForPath(String zkPath) throws InterruptedException {
    while (true) {
      try {
        ZooKeeper keeper = zookeeperConnect(zookeeper);
        if (keeper.exists(zkPath, false) != null) {
          debug("%s found", zkPath);
          return;
        }
        debug("Waiting for %s to be created", zkPath);
        Thread.sleep(3000);
      } catch (KeeperException|IOException e) {
        debug(e.getMessage());
        Thread.sleep(3000); 
      }
    } 
  }
  
  private void upload(String configName, String configDir) throws IOException, InterruptedException, KeeperException {
    if (wait) waitForPath("/collections"); // this is created by Solr when it first starts
    FileSystem fs = FileSystems.getDefault();
    Path configPath = fs.getPath(configDir);
    solr.uploadConfig(configPath, configName);
    System.out.println("config uploaded");
  }

  private void createCollection(String collection, String configName) throws IOException, SolrServerException, InterruptedException, KeeperException {
    if (wait) {
      String configPath = "/configs/" + configName;
      waitForPath(configPath);
    }
    CollectionAdminRequest.Create createRequest = new CollectionAdminRequest.Create();
    createRequest.setConfigName(configName);
    createRequest.setCollectionName(collection);
    createRequest.setNumShards(1);
    createRequest.setMaxShardsPerNode(1);
    while (true) {
      try {
        createRequest.process(solr);
        break;
      } catch (SolrServerException e) {
        debug(e.getMessage());
        sleep(1);
      }
    }
    System.out.printf("created collection %s\n", collection);
  }
  
  private void createAlias(String collection, String aliasName)   throws IOException, SolrServerException, InterruptedException, KeeperException {
    if (wait) {
      String collectionPath = "/collections/" + collection;
      waitForPath(collectionPath);
    }
    if (aliasName != null) {
      CollectionAdminRequest.CreateAlias createAliasRequest = new CollectionAdminRequest.CreateAlias();
      createAliasRequest.setAliasName(aliasName);
      createAliasRequest.setAliasedCollections(collection);
      createAliasRequest.process(solr);
      System.out.printf("alias %s created\n", aliasName);
    }
  }

  private HashMap<String, Object> get(HashMap<String, Object> hash, String name) {
    return (HashMap<String, Object>)hash.get(name);
  }
  
  
  /* Over-complex method to find out whether a collection has a replica on this node, based upon
   * the Solr Collection API ClusterStatus call. It returns a mess of NamedList and LinkedHashMap
   * objects that are hard to navigate. All we are really doing is extracting:
   * 
   *  /cluster/collections/$COLLECTION/shards/$SHARD/replicas/?/node_name
   */
   private boolean hasExistingReplica(String collection, String shard, String node) throws IOException, SolrServerException {
    
    CollectionAdminRequest.ClusterStatus statusRequest = new CollectionAdminRequest.ClusterStatus();
    statusRequest.setCollectionName(collection);
    NamedList<Object> response = statusRequest.process(solr).getResponse();

    HashMap<String, Object> collectionObj = (HashMap<String, Object>) response.findRecursive("cluster", "collections", collection);
    HashMap<String, Object> replicasObj = get(get(get(collectionObj, "shards"), shard), "replicas");
    for (String key : replicasObj.keySet()) {
      HashMap<String, Object> replica = get(replicasObj, key);
      String nodeName = (String)replica.get("node_name");
      if (nodeName.equals(node)) return true;
    }
    return false;
  }

   private void addReplica(String collection, String shard, String node) throws IOException, SolrServerException, InterruptedException {
     if (wait) {
       String collectionPath = "/collections/" + collection;
       waitForPath(collectionPath);
     }
     
     String solrNodeName = String.format("%s:8983_solr", node);

    if (hasExistingReplica(collection, shard, solrNodeName)) {
      System.out.println("Replica already present on this node");
      return;
    }
  
    CollectionAdminRequest.AddReplica addReplicaRequest = new CollectionAdminRequest.AddReplica();
    addReplicaRequest.setCollectionName(collection);
    addReplicaRequest.setShardName(shard);
    addReplicaRequest.setNode(solrNodeName);
    addReplicaRequest.process(solr);
    System.out.printf("replica of collection %s, shard %s added to node %s\n", collection, shard, node);
  }
  
  private void deleteReplica(String collection, String shard, String replica) {
    CollectionAdminRequest.DeleteReplica deleteReplicaRequest = new CollectionAdminRequest.DeleteReplica();
    deleteReplicaRequest.setCollectionName(collection);
    deleteReplicaRequest.setShardName(shard);
    deleteReplicaRequest.setReplica(replica);
  }

  private void addChroot() throws IOException, InterruptedException, KeeperException {
    if (zookeeper.endsWith(chroot)) {
      debug("removing chroot from end of ZK string: " + zookeeper);
      zookeeper = zookeeper.substring(0, zookeeper.length()-chroot.length());
    }
    
    ZooKeeper keeper = zookeeperConnect(zookeeper);
    if (keeper.exists(chroot, false)!=null) {
      debug("Chroot already exists at %s", chroot);
    } else {
      byte[] data = "".getBytes();
      keeper.create(chroot, data, ZooDefs.Ids.OPEN_ACL_UNSAFE,  CreateMode.PERSISTENT);
      debug("Zookeeper Chroot for %s created\n", chroot);
    }
  }

  private void uploadSolrXml(String solrXmlSourcePath) throws IOException, InterruptedException, KeeperException {
    waitForPath("/");
    
    ZooKeeper keeper = zookeeperConnect(zookeeper);
    byte[] data = IOUtils.toByteArray(new FileInputStream(solrXmlSourcePath));
    if (keeper.exists("/solr.xml", false)!=null) {
      debug("solr.xml already present in Zookeeper");
    }
    keeper.create("/solr.xml", data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    System.out.printf("Uploaded solr.xml to Zookeeper\n");
  }
  
  
  private static ZooKeeper zookeeperConnect(String zookeeper) throws IOException, InterruptedException {
    final CountDownLatch connectedSignal = new CountDownLatch(1);
    ZooKeeper zoo = new ZooKeeper(zookeeper,5000,new Watcher() {
         public void process(WatchedEvent we) {
            if (we.getState() == KeeperState.SyncConnected) {
               connectedSignal.countDown();
            }
         }
      });
      connectedSignal.await();
      return zoo;
  }

  private static String resolveZookeeperString(String zookeeper, String zookeeperPort, String chroot) throws NamingException {
    
    Hashtable<String,String> env = new Hashtable<String,String>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    InitialDirContext context = new InitialDirContext(env);
    
    if (zookeeper.contains(",")) {
      String[] hosts = StringUtils.split(zookeeper, ",");
      List<String> zkAddresses = new ArrayList<String>();
      for (String host : hosts) {
        zkAddresses.add(String.format("%s:%s",  host, zookeeperPort));
      }
      return StringUtils.join(zkAddresses, ",") + chroot;  
    }
    Attribute records = null;
    try {
      Attributes lookup = context.getAttributes(zookeeper, new String[] { "A" });
      records = lookup.get("A");
    } catch (NameNotFoundException e) {
      records = null;
    }
    if (records == null || records.size()==1) {
      return String.format("%s:%s%s",  zookeeper, zookeeperPort, chroot);
    } else {
      List<String> zkAddresses = new ArrayList<String>();
        for (int i = 0; i < records.size(); i++) {
        zkAddresses.add(String.format("%s:%s",  (String) records.get(i), zookeeperPort));
      }
      return StringUtils.join(zkAddresses, ",") + chroot;
    }
  }
  
  private static String getenv(String name, String defaultValue) {
    String value = System.getenv(name);
    if (value == null) {
      value = defaultValue;
    }
    return value;
  }

  private static void debug(String message) {
    System.err.println(message);
  }
  
  private static void debug(String message, Object... args) {
    System.err.println(String.format(message, (Object[])args));
  }
  
  private static void sleep(int seconds) {
    try {
      Thread.sleep(seconds * 1000);
    } catch (InterruptedException e) {
    }
  }

}

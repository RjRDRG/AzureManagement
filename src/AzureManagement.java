import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosDatabaseProperties;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.UniqueKey;
import com.azure.cosmos.models.UniqueKeyPolicy;
import com.microsoft.azure.CloudException;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.cosmosdb.CosmosDBAccount;
import com.microsoft.azure.management.cosmosdb.CosmosDBAccount.DefinitionStages.WithConsistencyPolicy;
import com.microsoft.azure.management.cosmosdb.CosmosDBAccount.DefinitionStages.WithCreate;
import com.microsoft.azure.management.cosmosdb.KeyKind;
import com.microsoft.azure.management.redis.RedisAccessKeys;
import com.microsoft.azure.management.redis.RedisCache;
import com.microsoft.azure.management.redis.RedisKeyType;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.resources.fluentcore.model.Creatable;
import com.microsoft.azure.management.storage.BlobContainer;
import com.microsoft.azure.management.storage.PublicAccess;
import com.microsoft.azure.management.storage.StorageAccount;
import com.microsoft.azure.management.storage.StorageAccountKey;
import com.microsoft.azure.management.storage.StorageAccountSkuType;
import com.microsoft.rest.LogLevel;

public class AzureManagement {

	public static void main(String[] args) {
		try {
			AzureConfig CONFIG = new AzureConfig(args[0]);

			final Map<String, Map<String, String>> props = new HashMap<String, Map<String, String>>();
			Arrays.stream(CONFIG.REGIONS).forEach(reg -> props.put(reg.name(), new HashMap<String, String>()));

			final Azure azure = createManagementClient(CONFIG.AZURE_AUTH_LOCATION);
			if (args.length >= 2 && args[1].equalsIgnoreCase("-delete")) {
				Arrays.stream(CONFIG.AZURE_RG_REGIONS).forEach(reg -> deleteResourceGroup(azure, reg));
			} else {
				// Init properties files
				for (String propF : CONFIG.AZURE_PROPS_LOCATIONS) {
					Files.deleteIfExists(Paths.get(propF));
					Files.write(Paths.get(propF),
							("# Date : " + new SimpleDateFormat().format(new Date()) + "\n").getBytes(),
							StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				}
				// Init settings files
				for (String propF : CONFIG.AZURE_SETTINGS_LOCATIONS) {
					Files.deleteIfExists(Paths.get(propF));
					Files.write(Paths.get(propF), "".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				}

				// Create resource groups
				for (int i = 0; i < CONFIG.REGIONS.length; i++)
					createResourceGroup(azure, CONFIG.AZURE_RG_REGIONS[i], CONFIG.REGIONS[i]);

				if (CONFIG.CREATE_STORAGE) {
					try {
						final Azure azure0 = createManagementClient(CONFIG.AZURE_AUTH_LOCATION);
						for (int i = 0; i < CONFIG.REGIONS.length; i++) {
							StorageAccount accountStorage = createStorageAccount(azure0, CONFIG.AZURE_RG_REGIONS[i],
									CONFIG.AZURE_STORAGE_NAME[i], CONFIG.REGIONS[i]);
							dumpStorageKey(props.get(CONFIG.REGIONS[i].name()), CONFIG.AZURE_PROPS_LOCATIONS[i],
									CONFIG.AZURE_SETTINGS_LOCATIONS[i], CONFIG.AZURE_APP_NAME[i], CONFIG.AZURE_FUNCTIONS_NAME[i],
									CONFIG.AZURE_RG_REGIONS[i], accountStorage);
							for (String cont : CONFIG.BLOB_CONTAINERS)
								createBlobContainer(azure0, CONFIG.AZURE_RG_REGIONS[i], CONFIG.AZURE_STORAGE_NAME[i], cont);
						}
						System.err.println("Azure Blobs Storage resources created with success");

					} catch (Exception e) {
						System.err.println("Error while creating storage resources");
						e.printStackTrace();
						System.exit(-1);
					}
				}

				if (CONFIG.CREATE_COSMOSDB) {
					try {
						final Azure azure0 = createManagementClient(CONFIG.AZURE_AUTH_LOCATION);
						CosmosDBAccount accountCosmosDB = createCosmosDBAccount(azure0, CONFIG.AZURE_RG_REGIONS[0],
								CONFIG.AZURE_COSMOSDB_NAME, CONFIG.REGIONS);
						for (int i = 0; i < CONFIG.REGIONS.length; i++) {
							dumpCosmosDBKey(props.get(CONFIG.REGIONS[i].name()), CONFIG.AZURE_PROPS_LOCATIONS[i],
									CONFIG.AZURE_SETTINGS_LOCATIONS[i], CONFIG.AZURE_APP_NAME[i], CONFIG.AZURE_FUNCTIONS_NAME[i],
									CONFIG.AZURE_RG_REGIONS[i], CONFIG.AZURE_COSMOSDB_DATABASE, accountCosmosDB);
						}
						CosmosClient cosmosClient = getCosmosClient(accountCosmosDB);
						createCosmosDatabase(cosmosClient, CONFIG.AZURE_COSMOSDB_DATABASE);

						//TODO: create the collections you have in your application
						createCosmosCollection(cosmosClient, CONFIG.AZURE_COSMOSDB_DATABASE, "Users", "/id", new String[0]);
						createCosmosCollection(cosmosClient, CONFIG.AZURE_COSMOSDB_DATABASE, "Channels", "/id", new String[0]);
						createCosmosCollection(cosmosClient, CONFIG.AZURE_COSMOSDB_DATABASE, "Messages", "/channel", new String[0]);

						System.err.println("Azure Cosmos DB resources created with success");
					} catch (Exception e) {
						System.err.println("Error while creating cosmos db resources");
						e.printStackTrace();
						System.exit(-1);
					}
				}

				if (CONFIG.CREATE_REDIS) {
					try {
						final Azure azure0 = createManagementClient(CONFIG.AZURE_AUTH_LOCATION);
						for (int i = 0; i < CONFIG.REGIONS.length; i++) {
							RedisCache cache = createRedis(azure0, CONFIG.AZURE_RG_REGIONS[i], CONFIG.AZURE_REDIS_NAME[i],
									CONFIG.REGIONS[i]);
							dumpRedisCacheInfo(props.get(CONFIG.REGIONS[i].name()), CONFIG.AZURE_PROPS_LOCATIONS[i],
									CONFIG.AZURE_SETTINGS_LOCATIONS[i], CONFIG.AZURE_APP_NAME[i], CONFIG.AZURE_FUNCTIONS_NAME[i],
									CONFIG.AZURE_RG_REGIONS[i], cache);
						}
						System.err.println("Azure Redis resources created with success");
					} catch (Exception e) {
						System.err.println("Error while creating redis resources");
						e.printStackTrace();
						System.exit(-1);
					}
				}

			}
		} catch (Exception e) {
			System.err.println("Error while creating resources");
			e.printStackTrace();
			System.exit(-1);
		}
		System.exit(0);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// Azure Group
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public static Azure createManagementClient(String authFile) throws CloudException, IOException {
		File credFile = new File(authFile);
		Azure azure = Azure.configure().withLogLevel(LogLevel.BASIC).authenticate(credFile).withDefaultSubscription();
		System.out.println("Azure client created with success");
		return azure;
	}

	public static void createResourceGroup(Azure azure, String rgName, Region region) {
		azure.resourceGroups().define(rgName).withRegion(region).create();
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// Azure Storage Account CODE
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public static StorageAccount createStorageAccount(Azure azure, String rgName, String name, Region region) {
		StorageAccount storageAccount = azure.storageAccounts().define(name).withRegion(region)
				.withNewResourceGroup(rgName).withGeneralPurposeAccountKindV2()
				.withAccessFromAllNetworks()
				.withSku(StorageAccountSkuType.STANDARD_LRS)
				.create();
		System.out.println("Storage account created with success: name = " + name + " ; group = " + rgName
				+ " ; region = " + region.name());
		return storageAccount;
	}

	private static BlobContainer createBlobContainer(Azure azure, String rgName, String accountName,
			String containerName) {
		BlobContainer container = azure.storageBlobContainers().defineContainer(containerName)
				.withExistingBlobService(rgName, accountName).withPublicAccess(PublicAccess.BLOB).create();
		System.out.println("Blob container created with success: name = " + containerName + " ; group = " + rgName
				+ " ; account = " + accountName);
		return container;
	}

	public static void recordStorageKey(Azure azure, String propFilename, String settingsFilename,
			String functionsName, String functionsRGName, StorageAccount account) throws IOException {
	}

	public static void dumpStorageKey(Map<String, String> props, String propFilename,
			String settingsFilename, String appName, String functionName, String rgName, StorageAccount account)
			throws IOException {
		List<StorageAccountKey> storageAccountKeys = account.getKeys();
		storageAccountKeys = account.regenerateKey(storageAccountKeys.get(0).keyName());

		String key = "DefaultEndpointsProtocol=https;AccountName=" +
				account.name() +
				";AccountKey=" +
				storageAccountKeys.get(0).value() +
				";EndpointSuffix=core.windows.net";

		props.put("BlobStoreConnection", key);

		Files.write(Paths.get(propFilename), ("BlobStoreConnection=" + key + "\n").getBytes(), StandardOpenOption.APPEND);

		StringBuilder cmd = new StringBuilder();
		if (functionName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(functionName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"BlobStoreConnection=");
			cmd.append(key);
			cmd.append("\"\n");
		}
		if (appName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"BlobStoreConnection=");
			cmd.append(key);
			cmd.append("\"\n");
		}

		Files.write(Paths.get(settingsFilename), cmd.toString().getBytes(), StandardOpenOption.APPEND);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// COSMOS DB CODE
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public static CosmosDBAccount createCosmosDBAccount(Azure azure, String rgName, String name, Region[] regions) {
		WithConsistencyPolicy step = azure.cosmosDBAccounts().define(name).withRegion(regions[0])
				.withExistingResourceGroup(rgName).withDataModelSql();
		CosmosDBAccount account;
		if (regions.length == 1) {
			account = step.withSessionConsistency().withWriteReplication(regions[0]).create();
		} else {
			WithCreate create = step.withSessionConsistency().withWriteReplication(regions[0])
					.withMultipleWriteLocationsEnabled(true);
			for (int i = 1; i < regions.length; i++) {
				create = create.withSessionConsistency().withWriteReplication(regions[i]);
			}
			account = create.create();
		}
		account.regenerateKey(KeyKind.PRIMARY);
		System.out.println("CosmosDB account created with success: name = " + name + " ; group = " + rgName
				+ " ; main region = " + regions[0].name() + " ; number regions = " + regions.length);
		return account;
	}

	public static void dumpCosmosDBKey(Map<String, String> props, String propFilename,
		String settingsFilename, String appName, String functionName, String rgName, String databaseName,
		CosmosDBAccount account) throws IOException {

		Files.write(Paths.get(propFilename),
				("COSMOSDB_KEY=" + account.listKeys().primaryMasterKey() + "\n").getBytes(),
				StandardOpenOption.APPEND);
		Files.write(Paths.get(propFilename), ("COSMOSDB_URL=" + account.documentEndpoint() + "\n").getBytes(),
				StandardOpenOption.APPEND);
		Files.write(Paths.get(propFilename), ("COSMOSDB_DATABASE=" + databaseName + "\n").getBytes(),
				StandardOpenOption.APPEND);


		props.put("COSMOSDB_KEY", account.listKeys().primaryMasterKey());
		props.put("COSMOSDB_URL", account.documentEndpoint());
		props.put("COSMOSDB_DATABASE", databaseName);

		StringBuilder cmd = new StringBuilder();
		if (appName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"COSMOSDB_KEY=");
			cmd.append(account.listKeys().primaryMasterKey());
			cmd.append("\"\n");
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"COSMOSDB_URL=");
			cmd.append(account.documentEndpoint());
			cmd.append("\"\n");
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"COSMOSDB_DATABASE=");
			cmd.append(databaseName);
			cmd.append("\"\n");
		}
		if (functionName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(functionName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"AzureCosmosDBConnection=AccountEndpoint=");
			cmd.append(account.documentEndpoint());
			cmd.append(";AccountKey=");
			cmd.append(account.listKeys().primaryMasterKey());
			cmd.append(";\"");
		}

		Files.write(Paths.get(settingsFilename), cmd.toString().getBytes(), StandardOpenOption.APPEND);
	}

	public static CosmosClient getCosmosClient(CosmosDBAccount account) {
		CosmosClient client = new CosmosClientBuilder().endpoint(account.documentEndpoint())
				.key(account.listKeys().primaryMasterKey()).directMode() // comment this is not to use direct mode
				.consistencyLevel(ConsistencyLevel.SESSION).connectionSharingAcrossClientsEnabled(true)
				.contentResponseOnWriteEnabled(true).buildClient();
		System.out.println("CosmosDB client created with success: name = " + account.name());
		return client;
	}

	static void createCosmosDatabase(CosmosClient client, String dbname) {
		// create database if not exists
		CosmosDatabaseProperties props = new CosmosDatabaseProperties(dbname);
		ThroughputProperties throughputProperties = ThroughputProperties.createManualThroughput(400);
		client.createDatabase(props, throughputProperties);
		System.out.println("CosmosDB database created with success: name = " + dbname);
	}

	static void createCosmosCollection(CosmosClient client, String dbname, String collectionName, String partKeys,
			String[] uniqueKeys) {
		try {
			CosmosDatabase db = client.getDatabase(dbname);
			CosmosContainerProperties props = new CosmosContainerProperties(collectionName, partKeys);
			if (uniqueKeys != null) {
				UniqueKeyPolicy uniqueKeyDef = new UniqueKeyPolicy();
				List<UniqueKey> uniqueKeyL = new ArrayList<UniqueKey>();
				for (String k : uniqueKeys) {
					uniqueKeyL.add(new UniqueKey(Collections.singletonList(k)));
				}
				uniqueKeyDef.setUniqueKeys(uniqueKeyL);
				props.setUniqueKeyPolicy(uniqueKeyDef);
			}
			db.createContainer(props);
			System.out.println("CosmosDB collection created with success: name = " + collectionName + "@" + dbname);

		} catch (Exception e) { // TODO: Something has gone terribly wrong.
			e.printStackTrace();
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// REDIS CODE
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	@SuppressWarnings("unchecked")
	public static RedisCache createRedis(Azure azure, String rgName, String name, Region region) {
		try {
			Creatable<RedisCache> redisCacheDefinition = azure.redisCaches().define(name).withRegion(region)
					.withNewResourceGroup(rgName).withBasicSku(0);

			return azure.redisCaches().create(redisCacheDefinition).get(redisCacheDefinition.key());
		} finally {
			System.out.println("Redis cache created with success: name = " + name + "@" + region);
		}
	}

	public static void dumpRedisCacheInfo(Map<String, String> props, String propFilename, String settingsFilename, String appName, String functionName, String rgName, RedisCache cache) throws IOException {
		RedisAccessKeys redisAccessKey = cache.regenerateKey(RedisKeyType.PRIMARY);

		Files.write(Paths.get(propFilename), ("REDIS_KEY=" + redisAccessKey.primaryKey() + "\n").getBytes(),
				StandardOpenOption.APPEND);
		Files.write(Paths.get(propFilename), ("REDIS_URL=" + cache.hostName() + "\n").getBytes(),
				StandardOpenOption.APPEND);

		props.put("REDIS_KEY", redisAccessKey.primaryKey());
		props.put("REDIS_URL", cache.hostName());

		StringBuilder cmd = new StringBuilder();
		if (appName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"REDIS_KEY=");
			cmd.append(redisAccessKey.primaryKey());
			cmd.append("\"\n");
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(appName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"REDIS_URL=");
			cmd.append(cache.hostName());
			cmd.append("\"\n");
		}
		if (functionName != null) {
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(functionName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"REDIS_KEY=");
			cmd.append(redisAccessKey.primaryKey());
			cmd.append("\"\n");
			cmd.append("az functionapp config appsettings set --name ");
			cmd.append(functionName);
			cmd.append(" --resource-group ");
			cmd.append(rgName);
			cmd.append(" --settings \"REDIS_URL=");
			cmd.append(cache.hostName());
			cmd.append("\"\n");
		}
		
		Files.write(Paths.get(settingsFilename), cmd.toString().getBytes(), StandardOpenOption.APPEND);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	////////////////////////////// AZURE DELETE CODE
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public static void deleteResourceGroup(Azure azure, String rgName) {
		azure.resourceGroups().deleteByName(rgName);
	}

}

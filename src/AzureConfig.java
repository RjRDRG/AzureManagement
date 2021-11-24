import com.microsoft.azure.management.resources.fluentcore.arm.Region;

import java.util.Arrays;
import java.util.Optional;

public class AzureConfig {

    // Auth file location
    // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!11
    // TODO: This file should be created by running in the console:
    // az ad sp create-for-rbac --sdk-auth > azure.auth
    public final String AZURE_AUTH_LOCATION;

    // TODO: These variable allow you to control what is being created
    public final boolean CREATE_STORAGE;
    public final boolean CREATE_COSMOSDB;
    public final boolean CREATE_REDIS;

    // TODO: change your suffix and other names if you want
    public final String MY_PREFIX;
    public final String MY_SUFFIX;

    public final String AZURE_COSMOSDB_NAME;	// Cosmos DB account name
    public final String AZURE_COSMOSDB_DATABASE;	// Cosmos DB database name
    public final String[] BLOB_CONTAINERS;	// Containers to add to the blob storage

    public final Region[] REGIONS; // Define the regions to deploy resources here

    // Name of resource group for each region
    public final String[] AZURE_RG_REGIONS;

    // Name of application server to be launched in each regions -- launching the application
    // server must be done using mvn, as you have been doing
    // TODO: this name should be the same as defined in your app
    public final String[] AZURE_APP_NAME;

    // Name of Blob storage account
    public final String[] AZURE_STORAGE_NAME;

    // Name of Redis server to be defined
    public final String[] AZURE_REDIS_NAME;

    // Name of Azure functions to be launched in each regions
    public final String[] AZURE_FUNCTIONS_NAME;

    // Name of property file with keys and URLS to access resources
    public final String[] AZURE_PROPS_LOCATIONS ;

    // Name of shell script file with commands to set application setting for you application server
    // and Azure functions
    public final String[] AZURE_SETTINGS_LOCATIONS;

    public AzureConfig(String suffix) {
        AZURE_AUTH_LOCATION = "azure.auth";

        CREATE_STORAGE = true;
        CREATE_COSMOSDB = true;
        CREATE_REDIS = true;

        MY_PREFIX= "scc2122";
        MY_SUFFIX = Optional.ofNullable(suffix).orElse("adrqrd");

        AZURE_COSMOSDB_NAME = MY_PREFIX + MY_SUFFIX;
        AZURE_COSMOSDB_DATABASE = MY_PREFIX + "db";
        BLOB_CONTAINERS = new String[]{"images"};

        REGIONS = new Region[] { Region.EUROPE_WEST };

        AZURE_RG_REGIONS = Arrays.stream(REGIONS)
                .map(reg -> MY_PREFIX + "-rg-" + reg.name() + "-" + MY_SUFFIX).toArray(String[]::new);

        AZURE_APP_NAME = Arrays.stream(REGIONS).map(reg -> MY_PREFIX + "-app-" + reg.name() + "-" + MY_SUFFIX)
                .toArray(String[]::new);

        AZURE_STORAGE_NAME = Arrays.stream(REGIONS).map(reg -> "sccstore" + reg.name() + MY_SUFFIX)
                .toArray(String[]::new);

        AZURE_REDIS_NAME = Arrays.stream(REGIONS).map(reg -> "redis" + reg.name() + MY_SUFFIX)
                .toArray(String[]::new);

        AZURE_FUNCTIONS_NAME = Arrays.stream(REGIONS).map(reg -> MY_PREFIX + "fun" + reg.name() + MY_SUFFIX)
                .toArray(String[]::new);

        AZURE_PROPS_LOCATIONS = Arrays.stream(REGIONS)
                .map(reg -> "azurekeys-" + reg.name() + ".props").toArray(String[]::new);

        AZURE_SETTINGS_LOCATIONS = Arrays.stream(REGIONS)
                .map(reg -> "azureprops-" + reg.name() + ".sh").toArray(String[]::new);
    }
}

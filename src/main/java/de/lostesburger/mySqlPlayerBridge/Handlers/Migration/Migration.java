package de.lostesburger.mySqlPlayerBridge.Handlers.Migration;

public class Migration {
    private boolean COMPLETED;
    private MigrationType migrationType;

    public Migration(boolean COMPLETED, MigrationType migrationType){
        this.COMPLETED = COMPLETED;
        this.migrationType = migrationType;
    }

    public MigrationType getMigrationType() { return this.migrationType; }
    public boolean isCOMPLETED() { return this.COMPLETED; }

}


package dk.ku.di.dms.vms.database.query.planner.operator.aggregate;

import dk.ku.di.dms.vms.database.query.planner.operator.result.RowOperatorResult;
import dk.ku.di.dms.vms.database.query.planner.operator.result.interfaces.IOperatorResult;
import dk.ku.di.dms.vms.database.store.table.Table;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IAggregate extends Supplier<IOperatorResult>, Consumer<RowOperatorResult> {

    Table getTable();

}

package dk.ku.di.dms.vms.database.query.planner.node.filter;

import dk.ku.di.dms.vms.database.query.planner.utils.IdentifiableNode;

import java.util.Collection;

/**
 * A data class to encapsulate the filter-related objects to apply to a given table
 * This is to avoid too many parameters in the constructor, leaving it convoluted
 */
public final class FilterInfo {

    /** The actual predicates */
    public final IFilter[] filters;

    /** The columns to probe */
    public final int[] filterColumns;

    /** The respective params of the predicates */
    public final Collection<IdentifiableNode<Object>> filterParams;

    public FilterInfo(IFilter[] filters, int[] filterColumns, Collection<IdentifiableNode<Object>> filterParams) {
        this.filters = filters;
        this.filterColumns = filterColumns;
        this.filterParams = filterParams;
    }

}
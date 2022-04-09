package dk.ku.di.dms.vms;

import dk.ku.di.dms.vms.modb.query.analyzer.QueryTree;
import dk.ku.di.dms.vms.modb.query.analyzer.exception.AnalyzerException;
import dk.ku.di.dms.vms.modb.query.planner.tree.PlanNode;
import dk.ku.di.dms.vms.modb.query.planner.Planner;
import org.junit.Test;

import java.util.List;

public class PlannerTest {

    @Test
    public void testJoinPlan() throws AnalyzerException {

        final QueryTree queryTree = TestCommon.getJoinQueryTree();

        final Planner planner = new Planner();

        PlanNode node = planner.plan( queryTree );

        assert node != null;
    }

    @Test
    public void testSimplePlan() throws AnalyzerException {
        final QueryTree queryTree = TestCommon.getSimpleQueryTree();
        assert true;
    }

    @Test
    public void testFilterCombinations(){

        Planner planner = new Planner();

        int[] filters = { 1, 2, 3, 4 };

        List<int[]> list = planner.getAllPossibleColumnCombinations( filters );

        assert  list != null;

    }


}
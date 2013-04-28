package com.subgraph.orchid.dashboard;

import java.io.IOException;
import java.io.PrintWriter;

public interface DashboardRenderer {
	void renderComponent(PrintWriter writer, Object component) throws IOException;
}

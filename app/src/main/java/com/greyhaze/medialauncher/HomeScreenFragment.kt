package com.greyhaze.medialauncher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.greyhaze.medialauncher.apps.AppsDrawerAdapter


/**
 * A simple [Fragment] subclass.
 * Use the [HomeScreenFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class HomeScreenFragment : Fragment() {
    var recyclerView: RecyclerView? = null
    var adapter: RecyclerView.Adapter<*>? = null
    var layoutManager: RecyclerView.LayoutManager? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home_screen, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.appDrawer_recylerView)
        adapter = AppsDrawerAdapter(requireContext())
        layoutManager = GridLayoutManager(context, 2)
        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter
    }
}
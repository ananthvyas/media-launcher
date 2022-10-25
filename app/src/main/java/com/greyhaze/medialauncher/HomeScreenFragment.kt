package com.greyhaze.medialauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
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
//        view.findViewById<Button>(R.id.power_button).setOnClickListener {
//            val i = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
//            i.putExtra("android.intent.extra.KEY_CONFIRM", false)
//            i.flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
//            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
//            requireContext().startActivity(i)
////            context?.let {
////                val applicationInfo =
////                    it.packageManager.getApplicationInfo(it.packageName, 0)
////                val isSystem =
////                    (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
////                            (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
////                Toast.makeText(context, "IsSystem $isSystem", Toast.LENGTH_SHORT).show()
////            }
////            pm.reboot("recovery")
//        }
        recyclerView = view.findViewById(R.id.appDrawer_recylerView)
        adapter = AppsDrawerAdapter(requireContext())
        layoutManager = GridLayoutManager(context, 2)
        recyclerView?.layoutManager = layoutManager
        recyclerView?.adapter = adapter
    }
}